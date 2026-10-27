package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteEnvelope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抄本抄进"设了文件夹密码"的文件夹：用虚构笔记把服务端建占位、客户端用文件夹钥匙加密、带指纹保存、
 * 再次右键的判断这几步串起来（游戏内的物品、网络、界面这部分要进游戏实测）。
 */
class FolderCopyTest {
	// 虚构的原笔记：中文、emoji、公式、冒号、多段
	private static final String SOURCE_BODY = "# 线性代数笔记\n\n特征值：$A\\mathbf{v} = \\lambda\\mathbf{v}$ 😀\n\n- 第一条：要点\n- 第二条：a:b:c\n";

	private static KeyRing.Created folder;
	private static String lockId;

	@TempDir
	Path vault;

	@BeforeAll
	static void createFolderKey() {
		folder = KeyRing.createFolder("虚构的文件夹密码".toCharArray(), false);
		lockId = KeyRing.folderId(folder.content());
	}

	@AfterAll
	static void destroyFolderKey() {
		folder.key().destroy();
	}

	private Vault open() throws Exception {
		Vault v = new Vault(vault);
		v.scan();
		return v;
	}

	/** 服务端：被锁的文件夹里只建占位笔记（createNote 传 ""），返回它和占位正文的指纹。 */
	private record Placeholder(NoteSummary note, String hash) {}

	private static Placeholder placeholder(Vault v, String path) throws Exception {
		NoteSummary note = v.createNote(path, "");
		return new Placeholder(note, Vault.hash(v.readBody(note.id()).orElseThrow()));
	}

	/** 客户端：解包、用文件夹钥匙按目标 ID 加密、带指纹保存。 */
	private static Vault.WriteResult clientSave(Vault v, UUID target, String packed) throws Exception {
		FolderCopy.Packed p = FolderCopy.unpack(packed).orElseThrow();
		FolderCopy.Sealed sealed = FolderCopy.seal(target, p.body(), lockId, folder.key());
		try {
			return v.writeBody(target, sealed.text(), p.targetHash());
		} finally {
			sealed.key().destroy();
		}
	}

	private static String decryptWithFolderKey(UUID id, String envelope) throws Exception {
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folder.key());
		ContentKey key = NoteEnvelope.unlock(id, envelope, new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId), wrapping);
		try {
			return NoteEnvelope.decrypt(id, envelope, key);
		} finally {
			key.destroy();
			java.util.Arrays.fill(wrapping, (byte) 0);
		}
	}

	private String sourceFile(NoteSummary source) throws Exception {
		return Files.readString(vault.resolve(source.relativePath()), StandardCharsets.UTF_8);
	}

	@Test
	void plainFolderCopyKeepsTheBodyAsIs() throws Exception {
		// 对照：没上锁的文件夹，服务端直接带正文新建
		Vault v = open();
		NoteSummary source = v.createNote("课程/线性代数", SOURCE_BODY);
		String before = sourceFile(source);
		NoteSummary copy = v.createNote("抄本/线性代数 抄本", v.readBody(source.id()).orElseThrow());
		assertEquals(SOURCE_BODY, v.readBody(copy.id()).orElseThrow());
		assertEquals(before, sourceFile(source), "原笔记不变");
	}

	@Test
	void lockedFolderCopyEncryptsTheFullBodyWithTheFolderKeyAndNeverWritesPlainText() throws Exception {
		Vault v = open();
		NoteSummary source = v.createNote("课程/线性代数", SOURCE_BODY);
		String before = sourceFile(source);
		Placeholder target = placeholder(v, "日记/线性代数 抄本");
		// 占位里只有默认标题，没有原文
		assertFalse(Files.readString(vault.resolve(target.note().relativePath())).contains("特征值"));
		// 只有默认标题时还不算抄好
		assertEquals(FolderCopy.Next.RESEND, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId));

		String packed = FolderCopy.pack(source.id(), target.hash(), v.readBody(source.id()).orElseThrow());
		assertEquals(Vault.WriteResult.OK, clientSave(v, target.note().id(), packed));

		String stored = v.readBody(target.note().id()).orElseThrow();
		assertTrue(NoteEnvelope.isEnvelope(stored));
		assertFalse(Files.readString(vault.resolve(target.note().relativePath())).contains("特征值"), "目标文件里没有明文");
		assertEquals(java.util.List.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId)), NoteEnvelope.locks(stored),
				"只有文件夹锁，保险库密码打不开");
		assertEquals(SOURCE_BODY, decryptWithFolderKey(target.note().id(), stored), "正文完整保留");
		assertEquals(before, sourceFile(source), "原笔记不变");
		assertEquals(FolderCopy.Next.FINISH, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId));
	}

	@Test
	void cancelledOrFailedSaveLeavesThePlaceholderSoTheNextRightClickResendsIntoTheSameNote() throws Exception {
		Vault v = open();
		NoteSummary source = v.createNote("课程/线性代数", SOURCE_BODY);
		Placeholder target = placeholder(v, "日记/线性代数 抄本");
		String packed = FolderCopy.pack(source.id(), target.hash(), SOURCE_BODY);

		// 第一次：玩家在输文件夹密码时取消了，什么都没存
		assertEquals(FolderCopy.Next.RESEND, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId));
		// 第二次：保存被拒（比如权限在中途变了），也什么都没写
		FolderCopy.Sealed sealed = FolderCopy.seal(target.note().id(), SOURCE_BODY, lockId, folder.key());
		assertEquals(Vault.WriteResult.DENIED, v.writeBody(target.note().id(), sealed.text(), target.hash(), n -> false));
		sealed.key().destroy();
		assertEquals(FolderCopy.Next.RESEND, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId));
		// 第三次：成功，抄进的还是同一篇（没有多建）
		int notes = v.list().size();
		assertEquals(Vault.WriteResult.OK, clientSave(v, target.note().id(), packed));
		assertEquals(notes, v.list().size());
		assertEquals(SOURCE_BODY, decryptWithFolderKey(target.note().id(), v.readBody(target.note().id()).orElseThrow()));
		assertEquals(FolderCopy.Next.FINISH, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId));
	}

	@Test
	void aTargetChangedOutsideTheGameIsNotOverwrittenAndTheNextRightClickCreatesANewOne() throws Exception {
		Vault v = open();
		NoteSummary source = v.createNote("课程/线性代数", SOURCE_BODY);
		Placeholder target = placeholder(v, "日记/线性代数 抄本");
		String packed = FolderCopy.pack(source.id(), target.hash(), SOURCE_BODY);
		// 抄写过程中，有人在外部编辑器里改了目标
		Path file = vault.resolve(target.note().relativePath());
		Files.writeString(file, Files.readString(file) + "外部写的内容\n");

		assertEquals(Vault.WriteResult.CONFLICT, clientSave(v, target.note().id(), packed));
		assertTrue(v.readBody(target.note().id()).orElseThrow().endsWith("外部写的内容\n"), "外部改的内容还在");
		assertEquals(FolderCopy.Next.CREATE, FolderCopy.next(v.readNote(target.note().id()), target.hash(), true, lockId),
				"不再往被改过的那篇里抄");
	}

	@Test
	void missingOrSomeoneElsesTargetMeansCreateANewOne() throws Exception {
		Vault v = open();
		Placeholder target = placeholder(v, "日记/线性代数 抄本");
		assertEquals(FolderCopy.Next.CREATE, FolderCopy.next(Optional.empty(), target.hash(), true, lockId));
		assertEquals(FolderCopy.Next.CREATE, FolderCopy.next(v.readNote(target.note().id()), target.hash(), false, lockId));
	}

	@Test
	void onlyACiphertextSealedWithExactlyTheRecordedFolderLockCountsAsFinished() throws Exception {
		Vault v = open();
		// 一篇本来就是加密笔记（比如用保险库密码加密的，或者别的文件夹锁）：不能因为"是密文"就算抄好
		NoteSummary other = v.createNote("日记/别的笔记", "");
		String otherHash = Vault.hash(v.readBody(other.id()).orElseThrow());
		KeyRing.Created otherFolder = KeyRing.createFolder("另一个文件夹密码".toCharArray(), false);
		try {
			FolderCopy.Sealed wrongLock = FolderCopy.seal(other.id(), "x", KeyRing.folderId(otherFolder.content()), otherFolder.key());
			wrongLock.key().destroy();
			assertEquals(Vault.WriteResult.OK, v.writeBody(other.id(), wrongLock.text(), null));
			assertEquals(FolderCopy.Next.CREATE, FolderCopy.next(v.readNote(other.id()), otherHash, true, lockId));
		} finally {
			otherFolder.key().destroy();
		}
		assertEquals(Vault.WriteResult.OK, v.writeBody(other.id(), "MPAL1:旧格式的密文", null));
		assertEquals(FolderCopy.Next.CREATE, FolderCopy.next(v.readNote(other.id()), otherHash, true, lockId));
		assertFalse(FolderCopy.sealedWith("MPAL2:坏掉的", lockId));
		assertFalse(FolderCopy.sealedWith("明文", lockId));
		FolderCopy.Sealed right = FolderCopy.seal(other.id(), "x", lockId, folder.key());
		right.key().destroy();
		assertTrue(FolderCopy.sealedWith(right.text(), lockId));
	}

	// ---- 服务端的抄写记录（物品上只存记录 ID，伪造的、别人的查不到） ----

	private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

	@Test
	void copyJobsAreOnlyFoundByTheirOwnerAndSurviveARestart() throws Exception {
		Path file = vault.resolve(".palace/copy-jobs.tsv");
		CopyJobs jobs = new CopyJobs(file);
		UUID source = UUID.randomUUID();
		UUID target = UUID.randomUUID();
		CopyJobs.Job job = jobs.start(ALICE, source, target, Vault.hash("占位"), lockId);
		assertEquals(job, jobs.find(job.id(), ALICE).orElseThrow());
		assertTrue(jobs.find(job.id(), BOB).isEmpty(), "别人的记录查不到");
		assertTrue(jobs.find(UUID.randomUUID(), ALICE).isEmpty(), "伪造的记录 ID 查不到");
		assertEquals(job, jobs.byTarget(target, ALICE).orElseThrow());
		assertTrue(jobs.byTarget(target, BOB).isEmpty());

		CopyJobs reloaded = new CopyJobs(file); // 服务器重启
		assertEquals(job, reloaded.find(job.id(), ALICE).orElseThrow());
		reloaded.finish(job.id());
		assertTrue(reloaded.find(job.id(), ALICE).isEmpty());
		assertTrue(new CopyJobs(file).find(job.id(), ALICE).isEmpty());
	}

	@Test
	void copyJobsKeepAtMostTwentyPerPlayerAndExpireAfterThirtyDays() throws Exception {
		long[] now = {1_000_000};
		CopyJobs jobs = new CopyJobs(vault.resolve(".palace/copy-jobs.tsv"), () -> now[0]);
		CopyJobs.Job first = jobs.start(ALICE, UUID.randomUUID(), UUID.randomUUID(), Vault.hash("a"), lockId);
		CopyJobs.Job bobs = jobs.start(BOB, UUID.randomUUID(), UUID.randomUUID(), Vault.hash("b"), lockId);
		for (int i = 0; i < CopyJobs.MAX_PER_PLAYER; i++) {
			now[0]++;
			jobs.start(ALICE, UUID.randomUUID(), UUID.randomUUID(), Vault.hash("c" + i), lockId);
		}
		assertTrue(jobs.find(first.id(), ALICE).isEmpty(), "最旧的被挤掉");
		assertTrue(jobs.find(bobs.id(), BOB).isPresent(), "别人的不受影响");
		now[0] += CopyJobs.MAX_AGE_MILLIS;
		assertTrue(jobs.find(bobs.id(), BOB).isEmpty(), "过期作废");
	}

	@Test
	void packRoundTripsBodiesWithColonsAndNewlinesAndRejectsMalformedInput() {
		UUID source = UUID.fromString("00000000-0000-4000-8000-000000000001");
		String hash = Vault.hash("占位");
		FolderCopy.Packed p = FolderCopy.unpack(FolderCopy.pack(source, hash, SOURCE_BODY)).orElseThrow();
		assertEquals(source, p.source());
		assertEquals(hash, p.targetHash());
		assertEquals(SOURCE_BODY, p.body());
		assertEquals("", FolderCopy.unpack(FolderCopy.pack(source, hash, "")).orElseThrow().body());
		for (String bad : new String[]{null, "", "no-colons", source + ":short:body", "not-a-uuid:" + hash + ":body",
				source + ":" + hash.toUpperCase() + ":body"}) {
			assertTrue(FolderCopy.unpack(bad).isEmpty(), String.valueOf(bad));
		}
	}
}
