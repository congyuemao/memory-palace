package com.memorypalace.core.vault;

import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteLinksTest {
	private static NoteSummary note(String path) {
		return new NoteSummary(UUID.nameUUIDFromBytes(path.getBytes()), path, path, false, NoteAccess.Rules.NONE);
	}

	private static final List<NoteSummary> NOTES = List.of(
			note("欢迎.md"), note("线代/特征值.md"), note("线代/矩阵.md"), note("数学/矩阵.md"),
			note("日记/2026-10-30.md"), note("a b/空 格.md"), note("Readme.md"));

	private static Optional<String> resolve(String source, String link) {
		return NoteLinks.resolve(NOTES, source, link).map(NoteSummary::relativePath);
	}

	@Test
	void wikiLinksResolveByFileName() {
		assertEquals(Optional.of("线代/特征值.md"), resolve("欢迎.md", "wiki:特征值"));
		assertEquals(Optional.of("线代/特征值.md"), resolve("欢迎.md", "wiki:特征值.md"));
		assertEquals(Optional.of("Readme.md"), resolve("欢迎.md", "wiki:readme"), "不分大小写");
		assertEquals(Optional.of("线代/特征值.md"), resolve("欢迎.md", "wiki:特征值#定义"), "#标题 只用来定位笔记");
		assertEquals(Optional.empty(), resolve("欢迎.md", "wiki:不存在"));
		assertEquals(Optional.empty(), resolve("欢迎.md", "wiki:#只有标题"));
	}

	@Test
	void duplicateNamesPreferTheSameFolder() {
		assertEquals(Optional.of("数学/矩阵.md"), resolve("数学/笔记.md", "wiki:矩阵"));
		assertEquals(Optional.of("线代/矩阵.md"), resolve("线代/特征值.md", "wiki:矩阵"));
		// 都不在同一个文件夹：路径一样长，按路径排序
		assertEquals(Optional.of("数学/矩阵.md"), resolve("欢迎.md", "wiki:矩阵"));
		assertEquals(Optional.of("线代/矩阵.md"), resolve("欢迎.md", "wiki:线代/矩阵"), "带文件夹就按路径找");
	}

	@Test
	void markdownLinksAreRelativeToTheNote() {
		assertEquals(Optional.of("线代/矩阵.md"), resolve("线代/特征值.md", "矩阵.md"));
		assertEquals(Optional.of("数学/矩阵.md"), resolve("线代/特征值.md", "../数学/矩阵.md"));
		assertEquals(Optional.of("欢迎.md"), resolve("线代/特征值.md", "/欢迎.md"), "以 / 开头从根目录算");
		assertEquals(Optional.of("a b/空 格.md"), resolve("欢迎.md", "a%20b/%E7%A9%BA%20%E6%A0%BC.md"), "百分号编码");
		assertEquals(Optional.of("日记/2026-10-30.md"), resolve("欢迎.md", "日记/2026-10-30"), "可以不写 .md");
		assertEquals(Optional.of("线代/特征值.md"), resolve("欢迎.md", "特征值.md"), "按路径找不到时按文件名找");
		assertEquals(Optional.empty(), resolve("欢迎.md", "../../外面.md"));
	}

	@Test
	void externalLinksAreNotNotes() {
		assertTrue(NoteLinks.isExternal("https://example.invalid/x"));
		assertTrue(NoteLinks.isExternal("mailto:someone@example.invalid"));
		assertFalse(NoteLinks.isExternal("wiki:特征值"));
		assertFalse(NoteLinks.isExternal("线代/特征值.md"));
		assertEquals(Optional.empty(), resolve("欢迎.md", "https://example.invalid/欢迎.md"));
	}

	@Test
	void parserTurnsWikiLinksIntoLinkSpans() {
		List<MdBlock> blocks = MarkdownParser.parse("见 [[特征值]] 和 [[线代/矩阵|矩阵笔记]]，`[[代码]]` 不算，![[图.png]] 也不算");
		List<MdSpan> spans = ((MdBlock.Paragraph) blocks.getFirst()).spans();
		assertEquals(new MdSpan("特征值", 0, "wiki:特征值"), spans.get(1));
		assertEquals(new MdSpan("矩阵笔记", 0, "wiki:线代/矩阵"), spans.get(3));
		assertTrue(spans.stream().noneMatch(s -> "wiki:代码".equals(s.link())));
		assertTrue(spans.stream().noneMatch(s -> "wiki:图.png".equals(s.link())));
		assertEquals(List.of("wiki:特征值", "wiki:线代/矩阵"), MarkdownParser.links(
				"见 [[特征值]] 和 [[线代/矩阵|矩阵笔记]]，`[[代码]]` 不算，![[图.png]] 也不算"));
		assertEquals(List.of("wiki:A", "b.md", "wiki:C"), MarkdownParser.links("# [[A]]\n\n- [x](b.md)\n\n> **[[C]]**"));
	}

	@TempDir
	Path dir;

	@Test
	void backlinksFindPlainNotesLinkingHere() throws Exception {
		Vault vault = new Vault(dir);
		NoteSummary target = vault.createNote("线代/特征值.md", "# 特征值\n");
		vault.createNote("欢迎.md", "从这里开始：[[特征值]]\n");
		vault.createNote("线代/矩阵.md", "相关：[特征值](特征值.md)\n");
		vault.createNote("无关.md", "[[别的]]\n");
		vault.createNote("自己.md", "[[自己]]\n");
		List<String> found = NoteLinks.backlinks(vault, target, n -> true).stream().map(NoteSummary::relativePath).toList();
		assertEquals(List.of("欢迎.md", "线代/矩阵.md"), found);
		assertEquals(List.of("欢迎.md"), NoteLinks.backlinks(vault, target, n -> !n.relativePath().startsWith("线代/"))
				.stream().map(NoteSummary::relativePath).toList(), "看不到的笔记不列出");
	}
}
