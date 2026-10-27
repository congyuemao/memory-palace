package com.memorypalace.core.vault;

import com.memorypalace.core.vault.NoteAccess.Rules;
import com.memorypalace.core.vault.Vault.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VaultAccessTest {
	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@TempDir
	Path dir;

	private Path write(String rel, String content) throws IOException {
		Path p = dir.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
		return p;
	}

	private String read(String rel) throws IOException {
		return Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
	}

	@Test
	void createNoteWritesOwnerAndReturnsOwnedSummary() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		String body = "# 合写\n内容\n";
		NoteSummary note = vault.createNote("课程/合写", body, ALICE);

		String text = read("课程/合写.md");
		assertEquals("---\npalace-id: " + note.id() + "\npalace-owner: " + ALICE + "\n---\n" + body, text);
		assertEquals(ALICE.toString(), FrontMatter.parse(text).fields().get(NoteAccess.OWNER_KEY));
		assertEquals(ALICE, note.access().owner());
	}

	@Test
	void createNoteWithoutOwnerOmitsOwnerField() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		String body = "# 无主\n内容";
		NoteSummary note = vault.createNote("无主", body);

		String text = read("无主.md");
		assertEquals("---\npalace-id: " + note.id() + "\n---\n" + body, text);
		assertFalse(FrontMatter.parse(text).fields().containsKey(NoteAccess.OWNER_KEY), "不写空的作者字段");
		assertNull(note.access().owner());
	}

	@Test
	void updateAccessWritesListsAndRefreshesIndexWithoutChangingOtherText() throws IOException {
		UUID id = UUID.randomUUID();
		String prefix = "---\r\npalace-id: " + id + "\r\n  tags: [线代, 期中]  \r\n"
				+ "# 保留注释\r\n\r\npalace-owner: " + ALICE + "\r\nx:  '原值'  \r\n";
		String body = "# 合写\r\n\r\npalace-read: 正文里的文字\r\n尾行  ";
		write("合写.md", prefix + "---\r\n" + body);
		var vault = new Vault(dir);
		vault.scan();
		Rules changed = new Rules(ALICE, Set.of(BOB), Set.of(Rules.EVERYONE_ID));

		assertEquals(WriteResult.OK, vault.updateAccess(id, r -> changed, n -> true));
		assertEquals(prefix + "palace-read: " + BOB + "\r\npalace-edit: everyone\r\n---\r\n" + body, read("合写.md"),
				"正文和其他字段的空白、注释、换行都保持原样");
		assertEquals(changed, vault.find(id).orElseThrow().access(), "不用重新扫描就能看到新权限");
	}

	@Test
	void updateAccessDoesNotRewriteEqualRules() throws IOException {
		UUID id = UUID.randomUUID();
		Path file = write("合写.md", "---\npalace-id: " + id + "\npalace-owner: " + ALICE
				+ "\npalace-read: [" + BOB + "]\npalace-edit: 'everyone'\n---\n# 合写\n内容");
		var vault = new Vault(dir);
		vault.scan();
		// 固定到过去的时间，避免文件系统时间精度让一次重写看起来像没写。
		Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
		FileTime beforeModified = Files.getLastModifiedTime(file);
		String before = read("合写.md");

		assertEquals(WriteResult.OK, vault.updateAccess(id, r -> new Rules(r.owner(), r.read(), r.edit()), n -> true));
		assertEquals(before, read("合写.md"), "相等但不是同一个实例的规则也不应重写或规范化字段");
		assertEquals(beforeModified, Files.getLastModifiedTime(file), "没有变化就不碰修改时间");
	}

	@Test
	void updateAccessDeniedLeavesFileUntouched() throws IOException {
		UUID id = UUID.randomUUID();
		Path file = write("私有.md", "---\npalace-id: " + id + "\npalace-owner: " + ALICE + "\n---\n# 私有\n内容");
		var vault = new Vault(dir);
		vault.scan();
		Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
		FileTime beforeModified = Files.getLastModifiedTime(file);
		String before = read("私有.md");

		assertEquals(WriteResult.DENIED, vault.updateAccess(id,
				r -> new Rules(r.owner(), Set.of(BOB), Set.of(BOB)), n -> false));
		assertEquals(before, read("私有.md"), "拒绝后不能改文件");
		assertEquals(beforeModified, Files.getLastModifiedTime(file));
		assertEquals(new Rules(ALICE, null, Set.of()), vault.find(id).orElseThrow().access());
	}

	@Test
	void updateAccessReturnsMissingForUnknownId() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		assertEquals(WriteResult.MISSING, vault.updateAccess(UUID.randomUUID(),
				r -> new Rules(r.owner(), Set.of(BOB), Set.of()), n -> true));
	}

	@Test
	void updateAccessChecksCurrentFileStateBeforeAllowingChange() throws IOException {
		UUID id = UUID.randomUUID();
		String prefix = "---\npalace-id: " + id + "\n";
		Path file = write("合写.md", prefix + "---\n# 合写\n内容");
		var vault = new Vault(dir);
		vault.scan();
		assertNull(vault.find(id).orElseThrow().access().owner());

		// 外部加上作者，不重新扫描；权限检查必须使用文件里的新作者。
		Files.writeString(file, prefix + "palace-owner: " + ALICE + "\n---\n# 合写\n内容", StandardCharsets.UTF_8);
		AtomicReference<UUID> seenOwner = new AtomicReference<>();
		WriteResult result = vault.updateAccess(id, r -> new Rules(r.owner(), Set.of(BOB), r.edit()), n -> {
			seenOwner.set(n.access().owner());
			return ALICE.equals(n.access().owner());
		});

		assertEquals(ALICE, seenOwner.get(), "allowed 收到的是刚从磁盘读出的作者");
		assertEquals(WriteResult.OK, result);
		assertEquals(new Rules(ALICE, Set.of(BOB), Set.of()), vault.find(id).orElseThrow().access());
		assertEquals(prefix + "palace-owner: " + ALICE + "\npalace-read: " + BOB + "\n---\n# 合写\n内容", read("合写.md"));
	}

	private static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");

	/**
	 * 回归：先单独授权 BOB、再公开给 everyone、最后取消 everyone（或者先公开、再授权 BOB、最后取消公开），
	 * BOB 的单独授权不能丢。每一步都经 updateAccess 写进文件，最后换一个新的 Vault 从硬盘重新读。
	 */
	@Test
	void individualGrantsSurviveRevokingEveryoneThroughDisk() throws IOException {
		for (NoteAccess.Level level : NoteAccess.Level.values()) {
			for (boolean individualFirst : new boolean[]{true, false}) {
				Path sub = Files.createDirectories(dir.resolve(level + "-" + individualFirst));
				UUID id = UUID.randomUUID();
				Files.writeString(sub.resolve("合写.md"), "---\npalace-id: " + id + "\npalace-owner: " + ALICE
						+ "\n---\n# 合写\n内容", StandardCharsets.UTF_8);
				var vault = new Vault(sub);
				vault.scan();
				var visibility = NoteAccess.Visibility.PRIVATE;
				java.util.List<UUID> order = individualFirst ? java.util.List.of(BOB, Rules.EVERYONE_ID)
						: java.util.List.of(Rules.EVERYONE_ID, BOB);
				for (UUID target : order) {
					assertEquals(WriteResult.OK, vault.updateAccess(id, r -> NoteAccess.share(r, target, level, visibility), n -> true));
				}
				assertEquals(WriteResult.OK, vault.updateAccess(id,
						r -> NoteAccess.unshare(r, Rules.EVERYONE_ID, visibility), n -> true));

				var reread = new Vault(sub);
				reread.scan();
				NoteSummary note = reread.find(id).orElseThrow();
				String what = level + (individualFirst ? "，先单独授权" : "，先公开");
				var bob = new NoteAccess.Who(BOB, false);
				var carol = new NoteAccess.Who(CAROL, false);
				assertTrue(note.canRead(bob, visibility), "BOB 的单独授权还在：" + what);
				assertEquals(level == NoteAccess.Level.EDIT, note.canEdit(bob), "BOB 的编辑权限照旧：" + what);
				assertFalse(note.canRead(carol, visibility), "取消公开后，没授权的人读不了：" + what);
				assertFalse(note.canEdit(carol), "取消公开后，没授权的人改不了：" + what);
				Set<UUID> expected = Set.of(BOB);
				assertEquals(level == NoteAccess.Level.READ ? expected : null, note.access().read(),
						"阅读名单保留缺省（null）和显式名单的区别：" + what);
				assertEquals(level == NoteAccess.Level.EDIT ? expected : Set.of(), note.access().edit(), what);
			}
		}
	}

	@Test
	void updateAccessRemovesReadAndEditFields() throws IOException {
		UUID id = UUID.randomUUID();
		String prefix = "---\npalace-id: " + id + "\npalace-owner: " + ALICE + "\n";
		String body = "# 合写\npalace-read: 正文保留\npalace-edit: 正文保留\n";
		write("合写.md", prefix + "palace-read: " + BOB + "\ntags: [线代]  \npalace-edit: everyone\n---\n" + body);
		var vault = new Vault(dir);
		vault.scan();

		assertEquals(WriteResult.OK, vault.updateAccess(id, r -> new Rules(r.owner(), null, Set.of()), n -> true));
		String text = read("合写.md");
		assertEquals(prefix + "tags: [线代]  \n---\n" + body, text, "删除分享字段，保留作者、标签和正文");
		assertFalse(FrontMatter.parse(text).fields().containsKey(NoteAccess.READ_KEY));
		assertFalse(FrontMatter.parse(text).fields().containsKey(NoteAccess.EDIT_KEY));
		assertEquals(new Rules(ALICE, null, Set.of()), vault.find(id).orElseThrow().access());
	}
}
