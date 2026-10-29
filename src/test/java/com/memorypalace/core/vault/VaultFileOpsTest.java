package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 新建文件夹、改名、删除（移进回收站）。全部在临时文件夹里。 */
class VaultFileOpsTest {
	@TempDir
	Path dir;

	private static final Instant NOW = Instant.parse("2026-10-30T01:02:03.456Z");

	@Test
	void createsNestedEmptyFolders() throws Exception {
		var vault = new Vault(dir);
		assertEquals("课程/线性代数", vault.createFolder("课程/线性代数/"));
		assertTrue(Files.isDirectory(dir.resolve("课程/线性代数")));
		assertThrows(IllegalArgumentException.class, () -> vault.createFolder("课程"), "已存在");
		for (String bad : new String[]{"", "  ", "/绝对", "../外面", ".palace/x", "a//b", "坏:名字"}) {
			assertThrows(IllegalArgumentException.class, () -> vault.createFolder(bad), bad);
		}
		assertFalse(Files.exists(dir.getParent().resolve("外面")));
	}

	@Test
	void renameKeepsTheIdAndFolder() throws Exception {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = vault.createNote("课程/旧名字", "# 内容\n");
		vault.createNote("课程/别的", "x");

		NoteSummary renamed = vault.renameNote(note.id(), "新 名字");
		assertEquals(note.id(), renamed.id());
		assertEquals("课程/新 名字.md", renamed.relativePath());
		assertFalse(Files.exists(dir.resolve("课程/旧名字.md")));
		assertTrue(Files.readString(dir.resolve("课程/新 名字.md"), StandardCharsets.UTF_8).contains("palace-id: " + note.id()));
		assertEquals("# 内容\n", vault.readBody(note.id()).orElseThrow());

		assertThrows(IllegalArgumentException.class, () -> vault.renameNote(note.id(), "别的"), "重名");
		assertThrows(IllegalArgumentException.class, () -> vault.renameNote(note.id(), "别处/名字"), "不能移动");
		assertThrows(IllegalArgumentException.class, () -> vault.renameNote(note.id(), "../逃出去"));

		// 只改大小写
		NoteSummary cased = vault.renameNote(note.id(), "ABC");
		NoteSummary lower = vault.renameNote(cased.id(), "abc");
		assertEquals("课程/abc.md", lower.relativePath());
		try (var files = Files.list(dir.resolve("课程"))) {
			assertTrue(files.anyMatch(f -> f.getFileName().toString().equals("abc.md")));
		}
	}

	@Test
	void deleteMovesNotesAndFoldersIntoTheTrash() throws Exception {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = vault.createNote("日记/今天", "# 今天\n");
		vault.createNote("课程/a/第一篇", "1");
		vault.createNote("课程/a/第二篇", "2");

		String trashed = vault.moveToTrash("日记/今天.md", NOW);
		assertEquals(".palace/trash/2026-10-30T01-02-03.456Z/日记/今天.md", trashed);
		assertTrue(vault.find(note.id()).isEmpty());
		assertEquals("---\npalace-id: " + note.id() + "\n---\n# 今天\n", Files.readString(dir.resolve(trashed), StandardCharsets.UTF_8));

		String folder = vault.moveToTrash("课程/a/", NOW);
		assertEquals(".palace/trash/2026-10-30T01-02-03.456Z_2/课程/a", folder);
		assertTrue(Files.isRegularFile(dir.resolve(folder).resolve("第二篇.md")));
		assertEquals(0, vault.list().size(), "回收站在 .palace 里，不会被扫成笔记");
		assertTrue(Files.isDirectory(dir.resolve("课程")), "上一级文件夹不动");

		assertThrows(IllegalArgumentException.class, () -> vault.moveToTrash("不存在", NOW));
		assertThrows(IllegalArgumentException.class, () -> vault.moveToTrash("../外面", NOW));
		assertThrows(IllegalArgumentException.class, () -> vault.moveToTrash(".palace", NOW));
		assertThrows(IllegalArgumentException.class, () -> vault.moveToTrash("", NOW));
	}
}
