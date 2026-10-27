package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FrontMatterTest {
	private static final UUID ID = UUID.fromString("7f3a2c1e-0000-4000-8000-000000000001");

	@Test
	void parsesFieldsAndBody() {
		var p = FrontMatter.parse("---\npalace-id: " + ID + "\ntags: [线代, 期中]\n---\n# 特征值\n正文");
		assertEquals(ID, p.id());
		assertEquals("[线代, 期中]", p.fields().get("tags"));
		assertEquals("# 特征值\n正文", p.body());
	}

	@Test
	void noFrontMatterMeansWholeTextIsBody() {
		var p = FrontMatter.parse("# 标题\n---\n不是 front matter");
		assertTrue(p.fields().isEmpty());
		assertNull(p.id());
		assertEquals("# 标题\n---\n不是 front matter", p.body());
	}

	@Test
	void unclosedFrontMatterIsIgnored() {
		var text = "---\npalace-id: " + ID + "\n# 忘了闭合";
		var p = FrontMatter.parse(text);
		assertNull(p.id());
		assertEquals(text, p.body());
	}

	@Test
	void handlesBomCrlfQuotesAndInvalidId() {
		var p = FrontMatter.parse("﻿---\r\npalace-id: \"" + ID + "\"\r\ntitle: '你好'\r\n---\r\n正文");
		assertEquals(ID, p.id());
		assertEquals("你好", p.fields().get("title"));
		assertEquals("正文", p.body());

		assertNull(FrontMatter.parse("---\npalace-id: 不是uuid\n---\n").id());
	}

	@Test
	void withIdAddsFrontMatterWhenMissing() {
		var out = FrontMatter.withId("# 标题\n正文", ID);
		assertEquals("---\npalace-id: " + ID + "\n---\n# 标题\n正文", out);
		assertEquals(ID, FrontMatter.parse(out).id());
	}

	@Test
	void withIdReplacesExistingIdAndKeepsOtherLines() {
		var old = UUID.fromString("00000000-0000-4000-8000-000000000000");
		var out = FrontMatter.withId("---\ntags: a\npalace-id: " + old + "\nx: y\n---\n正文", ID);
		assertEquals("---\ntags: a\npalace-id: " + ID + "\nx: y\n---\n正文", out);
	}

	@Test
	void withIdAppendsToExistingFrontMatterAndKeepsCrlf() {
		var out = FrontMatter.withId("﻿---\r\ntags: a\r\n---\r\n正文\r\n", ID);
		assertEquals("---\r\ntags: a\r\npalace-id: " + ID + "\r\n---\r\n正文\r\n", out);
	}

	@Test
	void withFieldReplacesExistingLineAndKeepsOtherLines() {
		String body = "# 标题\n\npalace-read: 正文里的字段不动\n末尾  ";
		String prefix = "---\npalace-id: " + ID + "\n  tags: [线代, 期中]  \n# 注释\n\n";
		String text = prefix + "  palace-read : '旧值'  \nx:  y  \n---\n" + body;
		assertEquals(prefix + "palace-read: everyone\nx:  y  \n---\n" + body,
				FrontMatter.withField(text, "palace-read", "everyone"), "只重写目标行，空白、注释和正文保持原样");
	}

	@Test
	void withFieldRewritesFirstDuplicateAndRemovesTheRest() {
		String text = "---\npalace-read: 旧值\ntags: a\n palace-read : 另一个值\nx: y\npalace-read: 第三个值\n---\n正文";
		assertEquals("---\npalace-read: everyone\ntags: a\nx: y\n---\n正文",
				FrontMatter.withField(text, "palace-read", "everyone"), "字段保留在第一次出现的位置");
	}

	@Test
	void withFieldNullRemovesAllMatchingLinesOnly() {
		String body = "# 正文\npalace-read: 正文保留\n---\n尾行  ";
		String text = "---  \npalace-read: a\n  tags: [线代]  \n# 注释\n\n palace-read : b\n"
				+ "palace-reader: 保留\nx: palace-read: 保留\n---\n" + body;
		assertEquals("---  \n  tags: [线代]  \n# 注释\n\npalace-reader: 保留\nx: palace-read: 保留\n---\n" + body,
				FrontMatter.withField(text, "palace-read", null), "只删除键完全相同的行");
	}

	@Test
	void withFieldAppendsBeforeClosingDelimiterWhenAbsent() {
		String text = "---\npalace-id: " + ID + "\ntags: a\n\n---\n正文\n";
		assertEquals("---\npalace-id: " + ID + "\ntags: a\n\npalace-read: everyone\n---\n正文\n",
				FrontMatter.withField(text, "palace-read", "everyone"));
	}

	@Test
	void withFieldAddsFrontMatterWhenMissing() {
		String body = "# 标题\n---\npalace-read: 正文\n末尾  ";
		assertEquals("---\npalace-read: everyone\n---\n" + body,
				FrontMatter.withField(body, "palace-read", "everyone"));
	}

	@Test
	void withFieldNullLeavesTextWithoutFrontMatterUnchanged() {
		String body = "# 标题\npalace-read: 正文\n---\n";
		assertEquals(body, FrontMatter.withField(body, "palace-read", null));
	}

	@Test
	void withFieldKeepsCrlfWhenReplacingAndRemoving() {
		String body = "# 标题\r\n\r\npalace-read: 正文\r\n末尾  ";
		String text = "---\r\ntags: a  \r\npalace-read: 旧值\r\npalace-read: 重复值\r\n---\r\n" + body;
		assertEquals("---\r\ntags: a  \r\npalace-read: everyone\r\n---\r\n" + body,
				FrontMatter.withField(text, "palace-read", "everyone"));
		assertEquals("---\r\ntags: a  \r\n---\r\n" + body,
				FrontMatter.withField(text, "palace-read", null));
	}

	@Test
	void withFieldStripsLeadingBomAndAppendsUsingCrlf() {
		String body = "# 标题\r\n正文里的 BOM：\uFEFF保留\r\n";
		String text = "\uFEFF---\r\ntags: a\r\n---\r\n" + body;
		assertEquals("---\r\ntags: a\r\npalace-read: everyone\r\n---\r\n" + body,
				FrontMatter.withField(text, "palace-read", "everyone"));
	}

	@Test
	void withFieldStripsBomWithoutChangingBodyWhenFrontMatterIsMissing() {
		String body = "# 标题\r\n正文\r\n";
		assertEquals("---\r\npalace-read: everyone\r\n---\r\n" + body,
				FrontMatter.withField("\uFEFF" + body, "palace-read", "everyone"));
		assertEquals(body, FrontMatter.withField("\uFEFF" + body, "palace-read", null));
	}
}
