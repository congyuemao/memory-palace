package com.memorypalace.core.markdown;

import com.memorypalace.core.markdown.MdBlock.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.memorypalace.core.markdown.MdSpan.*;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownParserTest {
	private static MdSpan s(String text, int styles) {
		return new MdSpan(text, styles, null);
	}

	@Test
	void emptyInput() {
		assertEquals(List.of(), MarkdownParser.parse(""));
		assertEquals(List.of(), MarkdownParser.parse(null));
	}

	@Test
	void headingsAndParagraphs() {
		var blocks = MarkdownParser.parse("# 特征值\n\n## 定义\n正文");
		assertEquals(List.of(
				new Heading(1, List.of(plain("特征值"))),
				new Heading(2, List.of(plain("定义"))),
				new Paragraph(List.of(plain("正文")))), blocks);
	}

	@Test
	void inlineStylesMergeAndNest() {
		var blocks = MarkdownParser.parse("普通**粗*粗斜*粗**~~删~~`代码`");
		assertEquals(List.of(new Paragraph(List.of(
				plain("普通"),
				s("粗", BOLD),
				s("粗斜", BOLD | ITALIC),
				s("粗", BOLD),
				s("删", STRIKE),
				s("代码", CODE)))), blocks);
	}

	@Test
	void lineBreaksArePreserved() {
		var blocks = MarkdownParser.parse("第一行\n第二行");
		assertEquals(List.of(new Paragraph(List.of(plain("第一行\n第二行")))), blocks);
	}

	@Test
	void links() {
		var blocks = MarkdownParser.parse("看[**这里**](https://example.com)");
		assertEquals(List.of(new Paragraph(List.of(
				plain("看"),
				new MdSpan("这里", BOLD, "https://example.com")))), blocks);
	}

	@Test
	void inlineMath() {
		var blocks = MarkdownParser.parse("特征值 $\\lambda$ 满足 $A v = \\lambda v$。价格 \\$5 和 $ 5 $ 都不是公式，`$x$` 也不是");
		assertEquals(List.of(new Paragraph(List.of(
				plain("特征值 "),
				s("\\lambda", MATH),
				plain(" 满足 "),
				s("A v = \\lambda v", MATH),
				plain("。价格 $5 和 $ 5 $ 都不是公式，"),
				s("$x$", CODE),
				plain(" 也不是")))), blocks);
	}

	@Test
	void blockMath() {
		var blocks = MarkdownParser.parse("前面\n\n$$\nA\\mathbf{v} = \\lambda \\mathbf{v}\n$$\n\n后面");
		assertEquals(List.of(
				new Paragraph(List.of(plain("前面"))),
				new MdBlock.Math("A\\mathbf{v} = \\lambda \\mathbf{v}"),
				new Paragraph(List.of(plain("后面")))), blocks);

		assertEquals(List.of(new MdBlock.Math("x^2")), MarkdownParser.parse("$$x^2$$"));
	}

	@Test
	void listsNestedOrderedAndTasks() {
		var blocks = MarkdownParser.parse("""
				- 苹果
				  - 青苹果
				3. 第三
				4. 第四
				- [x] 已完成
				- [ ] 待办
				""");
		assertEquals(List.of(
				new ListItem(0, "•", null, List.of(plain("苹果"))),
				new ListItem(1, "•", null, List.of(plain("青苹果"))),
				new ListItem(0, "3.", null, List.of(plain("第三"))),
				new ListItem(0, "4.", null, List.of(plain("第四"))),
				new ListItem(0, "•", true, List.of(plain("已完成"))),
				new ListItem(0, "•", false, List.of(plain("待办")))), blocks);
	}

	@Test
	void listItemWithSecondParagraph() {
		var blocks = MarkdownParser.parse("- 第一段\n\n  第二段\n");
		assertEquals(List.of(
				new ListItem(0, "•", null, List.of(plain("第一段"))),
				new ListItem(0, null, null, List.of(plain("第二段")))), blocks);
	}

	@Test
	void quotesCodeRule() {
		var blocks = MarkdownParser.parse("""
				> 引用
				> > 二层

				```java
				int x = 1;
				```

				---
				""");
		assertEquals(List.of(
				new Quote(1, List.of(plain("引用"))),
				new Quote(2, List.of(plain("二层"))),
				new Code("java", "int x = 1;"),
				new Rule()), blocks);
	}

	@Test
	void tables() {
		var blocks = MarkdownParser.parse("""
				| 名称 | 值 |
				|---|---|
				| **λ** | 2 |
				""");
		assertEquals(List.of(new Table(List.of(
				List.of(List.of(plain("名称")), List.of(plain("值"))),
				List.of(List.of(s("λ", BOLD)), List.of(plain("2")))))), blocks);
	}

	@Test
	void imagesAndHtml() {
		var blocks = MarkdownParser.parse("![示意图](a.png) ![](b.png) <b>粗</b>");
		assertEquals(List.of(new Paragraph(List.of(
				s("[图片: 示意图]", ITALIC),
				plain(" "),
				s("[图片]", ITALIC),
				plain(" <b>粗</b>")))), blocks);
	}
}
