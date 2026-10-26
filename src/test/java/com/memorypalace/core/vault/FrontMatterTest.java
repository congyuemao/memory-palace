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
}
