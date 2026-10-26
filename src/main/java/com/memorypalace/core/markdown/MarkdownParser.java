package com.memorypalace.core.markdown;

import java.util.List;

/**
 * Markdown 文本 → MdBlock 列表。底层用 commonmark-java（加 GFM 表格、删除线、任务列表扩展）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class MarkdownParser {
	private MarkdownParser() {}

	/**
	 * 规则（测试见 MarkdownParserTest）：
	 * - 相邻的同样式文字合并成一个 MdSpan；空文字的 span 不输出
	 * - 软换行和硬换行都变成 "\n"（跟 Obsidian 默认显示一致：写在下一行就显示在下一行）
	 * - 加粗/斜体/删除线/行内代码/链接可以嵌套，样式按位叠加；链接里的文字带 link
	 * - 行内公式：普通文字里的 $...$，内容非空、不以空格开头或结尾；"\$" 表示字面的 $（转义后不算公式）。
	 *   行内代码里的 $ 不算公式
	 * - 块级公式：一个段落去掉两端空白后以 $$ 开头、以 $$ 结尾（可以跨多行）→ MdBlock.Math
	 * - 图片：输出一个斜体 span "[图片: 替代文字]"（替代文字为空时 "[图片]"）
	 * - HTML 标签：当作普通文字原样输出
	 * - 列表里嵌套的代码块 / 引用照常输出成 Code / Quote 块（不记录缩进）
	 * - 永远不抛异常；null 或空字符串返回空列表
	 */
	public static List<MdBlock> parse(String markdown) {
		throw new UnsupportedOperationException("TODO");
	}
}
