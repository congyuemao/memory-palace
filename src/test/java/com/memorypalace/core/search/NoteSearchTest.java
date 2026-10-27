package com.memorypalace.core.search;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.vault.Vault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteSearchTest {
	@TempDir
	Path dir;
	Vault vault;

	private void write(String rel, String content) throws IOException {
		Path p = dir.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
	}

	@BeforeEach
	void setUp() throws IOException {
		write("线性代数/特征值.md", "# 特征值\n设 A 是方阵，Eigenvalue 满足 Av = λv。");
		write("线性代数/奇异值分解.md", "# 奇异值分解\n和特征值有关系，SVD 很常用。");
		write("课程/概率论.md", "# 概率论\n贝叶斯公式。");
		UUID id = UUID.randomUUID();
		var key = KeyRing.create("password1".toCharArray(), false).key();
		write("日记/特征值日记.md", "---\npalace-id: " + id + "\npalace-encrypted: v1\n---\n"
				+ NoteCipher.encrypt(key, id, "# 秘密\n今天学了特征值"));
		vault = new Vault(dir);
		vault.scan();
	}

	private List<String> paths(List<NoteSearch.Hit> hits) {
		return hits.stream().map(h -> h.note().relativePath()).toList();
	}

	@Test
	void titleBeatsPathBeatsBody() {
		var hits = NoteSearch.search(vault, "特征值", 10);
		assertEquals(List.of(
				"线性代数/特征值.md",          // 标题完全相同：1100
				"日记/特征值日记.md",           // 标题包含（加密笔记标题=文件名）：100
				"线性代数/奇异值分解.md"),      // 只命中正文：10
				paths(hits));
		assertEquals("", hits.get(0).snippet());
		assertTrue(hits.get(2).snippet().contains("特征值"), hits.get(2).snippet());
	}

	@Test
	void pathMatchesAndAllTermsMustMatch() {
		assertEquals(List.of("线性代数/奇异值分解.md", "线性代数/特征值.md"), paths(NoteSearch.search(vault, "线性代数", 10)));
		assertEquals(List.of("线性代数/奇异值分解.md"), paths(NoteSearch.search(vault, "线性代数 SVD", 10)));
		assertEquals(List.of(), paths(NoteSearch.search(vault, "线性代数 贝叶斯", 10)));
	}

	@Test
	void caseInsensitive() {
		assertEquals(List.of("线性代数/特征值.md"), paths(NoteSearch.search(vault, "eigenVALUE", 10)));
	}

	@Test
	void encryptedBodyIsNeverSearched() {
		// "今天学了" 只在加密笔记的正文里
		assertEquals(List.of(), NoteSearch.search(vault, "今天学了", 10));
		assertEquals(List.of(), NoteSearch.search(vault, "MPAL1", 10), "不能搜到密文");
	}

	@Test
	void limitAndBlankQuery() {
		assertEquals(1, NoteSearch.search(vault, "特征值", 1).size());
		assertEquals(List.of(), NoteSearch.search(vault, "   ", 10));
		assertEquals(List.of(), NoteSearch.search(vault, null, 10));
	}

	@Test
	void filterHidesUnreadableNotesEntirely() {
		// 读不了的笔记：标题、路径、正文都不该命中，也不该被读（这里用"正文里才有的词"验证）
		var hits = NoteSearch.search(vault, "特征值", 10, note -> !note.relativePath().startsWith("线性代数/"));
		assertEquals(List.of("日记/特征值日记.md"), paths(hits));
		assertEquals(List.of(), NoteSearch.search(vault, "SVD", 10, note -> !note.relativePath().contains("奇异值")));
		assertEquals(List.of("线性代数/奇异值分解.md"), paths(NoteSearch.search(vault, "SVD", 10, note -> true)));
	}

	@Test
	void snippetIsShortSingleLine() throws IOException {
		write("长文.md", "# 长文\n" + "前".repeat(100) + "关键词" + "后".repeat(100) + "\n第二行");
		vault.scan();
		var hit = NoteSearch.search(vault, "关键词", 10).get(0);
		assertTrue(hit.snippet().contains("关键词"));
		assertTrue(hit.snippet().length() <= 50, hit.snippet());
		assertTrue(hit.snippet().startsWith("…") && hit.snippet().endsWith("…"), hit.snippet());
		assertFalse(hit.snippet().contains("\n"));
	}
}
