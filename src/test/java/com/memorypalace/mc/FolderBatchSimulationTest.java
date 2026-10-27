package com.memorypalace.mc;

import com.memorypalace.core.batch.PacedBatch;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归：整个文件夹逐篇加密 / 解密时，保存会撞上服务端的保存限速（RateLimiter(1, 10)，房主也一样）。
 * 原来一口气发出所有保存，第 11 篇起被拒绝（BUSY）后就丢了；现在客户端用 PacedBatch 一次只发一篇，
 * BUSY 就等一会儿重试同一篇。这里用真的笔记库文件、真的限速器（模拟时钟）和真的加密，
 * 按客户端每 50 毫秒一刻的节奏模拟整个过程，检查硬盘上的实际结果。
 */
class FolderBatchSimulationTest {
	private static final UUID PLAYER = UUID.randomUUID();
	private static final String FOLDER = "日记";
	private static final long TICK_MS = 50;
	private static final long RETRY_MS = 1100;

	@TempDir
	Path dir;
	Vault vault;
	long nowMs;
	RateLimiter saveRate;
	MasterKey folderKey;
	String lockId;
	NoteEnvelope.Lock lock;

	/** 一篇要保存的：正文和服务端那边原来正文的指纹（保存时核对，防止覆盖外部修改）。 */
	record Save(UUID id, String body, String guard) {}

	@BeforeEach
	void setUp() throws IOException {
		Files.createDirectories(dir.resolve(FOLDER));
		vault = new Vault(dir);
		vault.scan();
		nowMs = 0;
		// 和 EditPayloads.SAVE_RATE 一样的参数，用模拟时钟
		saveRate = new RateLimiter(1, 10, () -> nowMs * 1_000_000L);
		KeyRing.Created created = KeyRing.createFolder("folder-password".toCharArray(), false);
		folderKey = created.key();
		lockId = KeyRing.folderId(created.content());
		lock = new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId);
		assertEquals(FolderLocks.WriteResult.OK, FolderLocks.write(vault, FOLDER, "", created.content()));
	}

	private Map<UUID, String> writeNotes(int count) throws IOException {
		Map<UUID, String> originals = new HashMap<>();
		for (int i = 1; i <= count; i++) {
			NoteSummary note = vault.createNote(FOLDER + "/第" + i + "篇", "# 第" + i + "篇\n短内容 " + i + "\n");
			originals.put(note.id(), vault.readBody(note.id()).orElseThrow());
		}
		return originals;
	}

	/** 模拟服务端处理一次保存：先过保存限速，再按指纹核对后写入（EditPayloads 的核心）。 */
	private PacedBatch.Outcome serverSave(Save save) throws IOException {
		if (!saveRate.tryTake(PLAYER)) {
			return PacedBatch.Outcome.BUSY;
		}
		Vault.WriteResult result = vault.writeBody(save.id(), save.body(), save.guard(), note -> true);
		return result == Vault.WriteResult.OK ? PacedBatch.Outcome.OK : PacedBatch.Outcome.FAILED;
	}

	/** 按客户端每刻 50 毫秒的节奏跑，直到队列空了或者 stopAfterDone 篇成功（模拟中途上锁）。 */
	private void run(PacedBatch<Save> batch, int stopAfterDone) throws IOException {
		for (int guard = 0; guard < 100_000 && !batch.idle(); guard++) {
			var next = batch.next(nowMs);
			if (next.isPresent()) {
				batch.result(serverSave(next.get()), nowMs);
				if (batch.counts().done() >= stopAfterDone) {
					return;
				}
			}
			nowMs += TICK_MS;
		}
		assertTrue(batch.idle(), "队列应该能跑完");
	}

	/** 客户端：把文件夹里还没用这把锁加密的笔记逐篇加密好排进队列（服务端 EncryptAll 发来的就是这些）。 */
	private PacedBatch<Save> encryptBatch() throws IOException {
		List<UUID> using = FolderLocks.notesUsing(vault, FOLDER, lockId).stream().map(NoteSummary::id).toList();
		PacedBatch<Save> batch = new PacedBatch<>(RETRY_MS, 30);
		for (NoteSummary note : FolderLocks.notesIn(vault, FOLDER)) {
			if (using.contains(note.id())) {
				continue;
			}
			String body = vault.readBody(note.id()).orElseThrow();
			byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
			ContentKey key = ContentKey.random();
			String sealed = NoteEnvelope.seal(note.id(), key, body, Map.of(lock, wrapping));
			batch.add(new Save(note.id(), sealed, Vault.hash(body)));
		}
		return batch;
	}

	private String decryptOnDisk(UUID id) throws IOException, WrongKeyException {
		String body = vault.readBody(id).orElseThrow();
		assertTrue(NoteEnvelope.isEnvelope(body), "硬盘上应该是 MPAL2 密文");
		assertTrue(NoteEnvelope.locks(body).contains(lock), "应该用的是这把文件夹锁");
		ContentKey key = NoteEnvelope.unlock(id, body, lock, NoteEnvelope.folderWrappingKey(folderKey));
		return NoteEnvelope.decrypt(id, body, key);
	}

	@Test
	void reproducesTheBug_firingTwelveSavesAtOnceLosesTheLastTwo() {
		int accepted = 0;
		for (int i = 0; i < 12; i++) {
			if (saveRate.tryTake(PLAYER)) {
				accepted++;
			}
		}
		assertEquals(10, accepted, "一口气发 12 次保存，限速只放过 10 次（原来的做法会丢 2 篇）");
		nowMs += 1100;
		assertTrue(saveRate.tryTake(PLAYER), "等 1.1 秒后又能通过一次");
	}

	@Test
	void pacedBatchEncryptsAllTwelveShortNotes() throws Exception {
		Map<UUID, String> originals = writeNotes(12);
		PacedBatch<Save> batch = encryptBatch();
		run(batch, Integer.MAX_VALUE);

		assertEquals(new PacedBatch.Counts(12, 12, 0, 0), batch.counts(), "12 篇全部成功");
		for (var entry : originals.entrySet()) {
			assertEquals(entry.getValue(), decryptOnDisk(entry.getKey()), "解开后和原文一样");
		}
		assertEquals(12, FolderLocks.notesUsing(vault, FOLDER, lockId).size());
		assertTrue(nowMs >= 2 * RETRY_MS, "超过 10 篇的部分是等限速恢复后重试成功的，不是被放宽了限速");
	}

	@Test
	void pacedBatchHandlesManyMoreNotesThanTheBurst() throws Exception {
		Map<UUID, String> originals = writeNotes(25);
		PacedBatch<Save> batch = encryptBatch();
		run(batch, Integer.MAX_VALUE);
		assertEquals(new PacedBatch.Counts(25, 25, 0, 0), batch.counts());
		for (var entry : originals.entrySet()) {
			assertEquals(entry.getValue(), decryptOnDisk(entry.getKey()));
		}
	}

	@Test
	void interruptedEncryptionResumesWithoutTouchingFinishedNotes() throws Exception {
		Map<UUID, String> originals = writeNotes(15);
		PacedBatch<Save> first = encryptBatch();
		run(first, 6);
		// 中途上锁：排着队的取消，报告未处理几篇
		List<Save> cancelled = first.cancelQueued();
		assertEquals(new PacedBatch.Counts(15, 6, 0, 9), first.counts(), "成功 6、失败 0、未处理 9");
		assertEquals(9, cancelled.size());

		// 记下已经加密好的 6 篇在硬盘上的样子：继续时不能再动它们
		Map<UUID, String> finished = new HashMap<>();
		for (NoteSummary note : FolderLocks.notesUsing(vault, FOLDER, lockId)) {
			finished.put(note.id(), Files.readString(dir.resolve(note.relativePath()), StandardCharsets.UTF_8));
		}
		assertEquals(6, finished.size());

		// 再执行一次 /palace lockfolder：服务端只发还没加密的 9 篇
		PacedBatch<Save> resumed = encryptBatch();
		assertEquals(9, resumed.counts().total(), "已经加密的不会重复处理");
		run(resumed, Integer.MAX_VALUE);
		assertEquals(new PacedBatch.Counts(9, 9, 0, 0), resumed.counts());

		for (var entry : finished.entrySet()) {
			NoteSummary note = vault.find(entry.getKey()).orElseThrow();
			assertEquals(entry.getValue(), Files.readString(dir.resolve(note.relativePath()), StandardCharsets.UTF_8),
					"第一次已经完成的密文一个字节都没变");
		}
		for (var entry : originals.entrySet()) {
			assertEquals(entry.getValue(), decryptOnDisk(entry.getKey()));
		}
	}

	@Test
	void externalEditDuringTheBatchIsNotOverwrittenAndIsPickedUpOnResume() throws Exception {
		writeNotes(12);
		PacedBatch<Save> batch = encryptBatch();
		// 客户端已经按旧内容加密好、排进队列之后，其中一篇在发出之前被外部（Obsidian）改了
		NoteSummary changed = vault.resolve(FOLDER + "/第12篇").orElseThrow();
		String edited = "# 外部改过\n新内容\n";
		vault.writeBody(changed.id(), edited, null);

		run(batch, Integer.MAX_VALUE);
		assertEquals(new PacedBatch.Counts(12, 11, 1, 0), batch.counts(), "冲突不重试，算失败");
		assertEquals(edited, vault.readBody(changed.id()).orElseThrow(), "外部修改没有被旧内容的密文覆盖");

		PacedBatch<Save> resumed = encryptBatch();
		run(resumed, Integer.MAX_VALUE);
		assertEquals(new PacedBatch.Counts(1, 1, 0, 0), resumed.counts());
		assertEquals(edited, decryptOnDisk(changed.id()), "继续加密时加密的是外部改过的新内容");
	}

	@Test
	void pacedDecryptionOfTwelveNotesThenTheLockCanBeDeleted() throws Exception {
		Map<UUID, String> originals = writeNotes(12);
		run(encryptBatch(), Integer.MAX_VALUE);

		// /palace unlockfolder：逐篇解密存回明文
		PacedBatch<Save> decrypt = new PacedBatch<>(RETRY_MS, 30);
		for (NoteSummary note : FolderLocks.notesUsing(vault, FOLDER, lockId)) {
			String body = vault.readBody(note.id()).orElseThrow();
			ContentKey key = NoteEnvelope.unlock(note.id(), body, lock, NoteEnvelope.folderWrappingKey(folderKey));
			decrypt.add(new Save(note.id(), NoteEnvelope.decrypt(note.id(), body, key), Vault.hash(body)));
		}
		run(decrypt, Integer.MAX_VALUE);
		assertEquals(new PacedBatch.Counts(12, 12, 0, 0), decrypt.counts());
		for (var entry : originals.entrySet()) {
			assertEquals(entry.getValue(), vault.readBody(entry.getKey()).orElseThrow(), "改回了原来的明文");
		}
		// 服务端删锁前的检查：已经没有笔记用着这把锁，才能删
		assertTrue(FolderLocks.notesUsing(vault, FOLDER, lockId).isEmpty());
		String content = FolderLocks.read(vault, FOLDER).orElseThrow();
		assertEquals(FolderLocks.WriteResult.OK, FolderLocks.delete(vault, FOLDER, Vault.hash(content)));
		assertTrue(FolderLocks.lockedFolder(vault, FOLDER).isEmpty());
	}

	@Test
	void interruptedDecryptionKeepsTheLockForTheRemainingNotes() throws Exception {
		writeNotes(12);
		run(encryptBatch(), Integer.MAX_VALUE);
		PacedBatch<Save> decrypt = new PacedBatch<>(RETRY_MS, 30);
		for (NoteSummary note : FolderLocks.notesUsing(vault, FOLDER, lockId)) {
			String body = vault.readBody(note.id()).orElseThrow();
			ContentKey key = NoteEnvelope.unlock(note.id(), body, lock, NoteEnvelope.folderWrappingKey(folderKey));
			decrypt.add(new Save(note.id(), NoteEnvelope.decrypt(note.id(), body, key), Vault.hash(body)));
		}
		run(decrypt, 5);
		decrypt.cancelQueued(); // 中途上锁：排着队的明文丢掉
		assertEquals(new PacedBatch.Counts(12, 5, 0, 7), decrypt.counts());
		assertEquals(7, FolderLocks.notesUsing(vault, FOLDER, lockId).size(), "还有 7 篇用着这把锁：锁不能删");
		// 剩下的 7 篇照样能用文件夹密码打开
		for (NoteSummary note : FolderLocks.notesUsing(vault, FOLDER, lockId)) {
			assertNotNull(decryptOnDisk(note.id()));
		}
	}
}
