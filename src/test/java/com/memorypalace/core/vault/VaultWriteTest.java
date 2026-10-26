package com.memorypalace.core.vault;

import com.memorypalace.core.vault.Vault.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultWriteTest {
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
	void hashIgnoresLineEndingStyle() {
		assertEquals(Vault.hash("a\nb"), Vault.hash("a\r\nb"));
		assertNotEquals(Vault.hash("a\nb"), Vault.hash("a\nc"));
		assertEquals(64, Vault.hash("").length());
		assertEquals(Vault.hash("x").toLowerCase(), Vault.hash("x"));
	}

	@Test
	void writeKeepsFrontMatterAndUpdatesTitle() throws IOException {
		UUID id = UUID.randomUUID();
		write("线代/a.md", "---\ntags: [线代]\npalace-id: " + id + "\n---\n# 旧标题\n旧正文");
		var vault = new Vault(dir);
		vault.scan();
		String oldBody = vault.readBody(id).orElseThrow();

		assertEquals(WriteResult.OK, vault.writeBody(id, "# 新标题\n新正文", Vault.hash(oldBody)));
		assertEquals("---\ntags: [线代]\npalace-id: " + id + "\n---\n# 新标题\n新正文", read("线代/a.md"));
		assertEquals("新标题", vault.find(id).orElseThrow().title());
		assertEquals("# 新标题\n新正文", vault.readBody(id).orElseThrow());
	}

	@Test
	void conflictWhenChangedOutside() throws IOException {
		UUID id = UUID.randomUUID();
		Path file = write("a.md", "---\npalace-id: " + id + "\n---\n原文");
		var vault = new Vault(dir);
		vault.scan();
		String opened = Vault.hash(vault.readBody(id).orElseThrow());

		// 编辑器开着的时候，外部编辑器改了文件
		Files.writeString(file, "---\npalace-id: " + id + "\n---\n外部改的", StandardCharsets.UTF_8);
		assertEquals(WriteResult.CONFLICT, vault.writeBody(id, "我写的", opened));
		assertTrue(read("a.md").endsWith("外部改的"), "冲突时不能写");

		// 用户选择覆盖
		assertEquals(WriteResult.OK, vault.writeBody(id, "我写的", null));
		assertTrue(read("a.md").endsWith("我写的"));
	}

	@Test
	void keepsCrlfStyle() throws IOException {
		UUID id = UUID.randomUUID();
		write("a.md", "---\r\npalace-id: " + id + "\r\n---\r\n第一行\r\n第二行");
		var vault = new Vault(dir);
		vault.scan();
		assertEquals(WriteResult.OK, vault.writeBody(id, "新一\n新二", Vault.hash("第一行\n第二行")));
		assertEquals("---\r\npalace-id: " + id + "\r\n---\r\n新一\r\n新二", read("a.md"));
	}

	@Test
	void missingNote() throws IOException {
		UUID id = UUID.randomUUID();
		Path file = write("a.md", "---\npalace-id: " + id + "\n---\nx");
		var vault = new Vault(dir);
		vault.scan();
		Files.delete(file);
		assertEquals(WriteResult.MISSING, vault.writeBody(id, "y", null));
		assertEquals(WriteResult.MISSING, vault.writeBody(UUID.randomUUID(), "y", null));
	}

	@Test
	void writeFollowsRename() throws IOException {
		UUID id = UUID.randomUUID();
		Path file = write("a.md", "---\npalace-id: " + id + "\n---\nx");
		var vault = new Vault(dir);
		vault.scan();
		Files.move(file, dir.resolve("b.md"));
		assertEquals(WriteResult.OK, vault.writeBody(id, "y", Vault.hash("x")));
		assertTrue(read("b.md").endsWith("y"));
		assertFalse(Files.exists(file));
	}

	@Test
	void createNoteWithFoldersAndDefaultBody() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = vault.createNote("课程/线代/新笔记", "");
		assertEquals("课程/线代/新笔记.md", note.relativePath());
		assertEquals("新笔记", note.title());
		assertTrue(vault.find(note.id()).isPresent());

		var parsed = FrontMatter.parse(read("课程/线代/新笔记.md"));
		assertEquals(note.id(), parsed.id());
		assertEquals("# 新笔记\n\n", parsed.body());

		NoteSummary second = vault.createNote("随手记.md", "# 灵感\n内容");
		assertEquals("灵感", second.title());
		assertEquals("# 灵感\n内容", vault.readBody(second.id()).orElseThrow());
	}

	@Test
	void createNoteRejectsBadPathsAndExisting() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		vault.createNote("a", "");
		for (String bad : new String[]{"a", "a.md", "../外面", "x/../../外面", "/abs", "C:/abs", "", "x/", ".隐藏/a",
				"文件夹/.a", "坏<名>", "问?号", "x//y"}) {
			assertThrows(IllegalArgumentException.class, () -> vault.createNote(bad, ""), bad);
		}
		assertFalse(Files.exists(dir.getParent().resolve("外面.md")));
	}
}
