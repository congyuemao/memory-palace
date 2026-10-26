package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultTest {
	@TempDir
	Path dir;

	private Path write(String rel, String content) throws IOException {
		Path p = dir.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
		return p;
	}

	@Test
	void createsMissingRoot() throws IOException {
		Path root = dir.resolve("a/b/vault");
		var vault = new Vault(root);
		assertTrue(Files.isDirectory(root));
		vault.scan();
		assertTrue(vault.list().isEmpty());
	}

	@Test
	void scanAssignsIdsAndWritesThemBack() throws IOException {
		Path file = write("线性代数/特征值.md", "# 特征值\n$$A v = \\lambda v$$");
		var vault = new Vault(dir);
		vault.scan();

		var notes = vault.list();
		assertEquals(1, notes.size());
		var note = notes.get(0);
		assertEquals("线性代数/特征值.md", note.relativePath());
		assertEquals("特征值", note.title());
		assertEquals("线性代数", note.folder());

		// ID 已写回文件，而且正文没被改
		var parsed = FrontMatter.parse(Files.readString(file, StandardCharsets.UTF_8));
		assertEquals(note.id(), parsed.id());
		assertEquals("# 特征值\n$$A v = \\lambda v$$", parsed.body());

		// 再扫一次 ID 不变
		vault.scan();
		assertEquals(note.id(), vault.list().get(0).id());
	}

	@Test
	void titleFallsBackToFileName() throws IOException {
		write("随手记.md", "没有标题的正文");
		var vault = new Vault(dir);
		vault.scan();
		assertEquals("随手记", vault.list().get(0).title());
	}

	@Test
	void skipsHiddenFoldersAndNonMarkdown() throws IOException {
		write(".obsidian/workspace.md", "# 不该出现");
		write(".palace/x.md", "# 不该出现");
		write("图片.png", "not a note");
		write("A.MD", "# 大写扩展名");
		var vault = new Vault(dir);
		vault.scan();
		assertEquals(1, vault.list().size());
		assertEquals("A.MD", vault.list().get(0).relativePath());
	}

	@Test
	void duplicateIdsGetSplit() throws IOException {
		UUID same = UUID.randomUUID();
		write("a.md", "---\npalace-id: " + same + "\n---\nA");
		write("b.md", "---\npalace-id: " + same + "\n---\nB");
		var vault = new Vault(dir);
		vault.scan();

		var notes = vault.list();
		assertEquals(2, notes.size());
		assertEquals(same, notes.get(0).id()); // a.md 字典序在前，保留原 ID
		assertNotEquals(same, notes.get(1).id());
		assertEquals(notes.get(1).id(),
				FrontMatter.parse(Files.readString(dir.resolve("b.md"))).id());
	}

	@Test
	void listFolderOnlyReturnsDirectChildren() throws IOException {
		write("课/线代/a.md", "A");
		write("课/线代/b.md", "B");
		write("课/线代/习题/c.md", "C");
		write("根.md", "R");
		var vault = new Vault(dir);
		vault.scan();

		assertEquals(2, vault.listFolder("课/线代").size());
		assertEquals(2, vault.listFolder("/课/线代/").size());
		assertEquals(1, vault.listFolder("").size());
		assertTrue(vault.listFolder("不存在").isEmpty());
	}

	@Test
	void resolveAcceptsVariantsAndRejectsEscapes() throws IOException {
		// 笔记库是 dir/vault，dir/outside.md 在库外面
		write("vault/线代/特征值.md", "# X");
		write("outside.md", "# 库外面的文件");
		var vault = new Vault(dir.resolve("vault"));
		vault.scan();

		assertTrue(vault.resolve("线代/特征值.md").isPresent());
		assertTrue(vault.resolve("线代/特征值").isPresent());
		assertTrue(vault.resolve("线代\\特征值.md").isPresent());
		assertTrue(vault.resolve("../outside.md").isEmpty());
		assertTrue(vault.resolve("线代/../../outside").isEmpty());
		assertTrue(vault.resolve(dir.resolve("vault/线代/特征值.md").toAbsolutePath().toString()).isEmpty());
		assertTrue(vault.resolve("").isEmpty());
	}

	@Test
	void readBodyFollowsRenamesAndHandlesDeletes() throws IOException {
		Path file = write("old.md", "# 标题\n正文");
		var vault = new Vault(dir);
		vault.scan();
		UUID id = vault.list().get(0).id();

		assertEquals("# 标题\n正文", vault.readBody(id).orElseThrow());

		// 在外部编辑器里改名 / 移动文件夹
		Path moved = dir.resolve("新文件夹/new.md");
		Files.createDirectories(moved.getParent());
		Files.move(file, moved);
		assertEquals("# 标题\n正文", vault.readBody(id).orElseThrow());
		assertEquals(moved.toAbsolutePath().normalize(), vault.pathOf(id).orElseThrow().toAbsolutePath().normalize());

		Files.delete(moved);
		assertTrue(vault.readBody(id).isEmpty());
		assertTrue(vault.find(UUID.randomUUID()).isEmpty());
	}

	@Test
	void readOnlyFileWithoutIdIsSkippedNotFatal() throws IOException {
		write("好.md", "# 好");
		Path locked = write("只读.md", "# 只读");
		assertTrue(locked.toFile().setReadOnly());
		try {
			var vault = new Vault(dir);
			assertDoesNotThrow(vault::scan);
			assertTrue(vault.list().stream().anyMatch(n -> n.title().equals("好")));
		} finally {
			locked.toFile().setWritable(true);
		}
	}

	@Test
	void unreadableFileDoesNotBreakScan() throws IOException {
		write("好.md", "# 好");
		// 非法 UTF-8 字节
		Files.write(dir.resolve("坏.md"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF});
		var vault = new Vault(dir);
		assertDoesNotThrow(vault::scan);
		assertTrue(vault.list().stream().anyMatch(n -> n.title().equals("好")));
	}
}
