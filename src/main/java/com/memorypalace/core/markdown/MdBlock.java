package com.memorypalace.core.markdown;

import java.util.List;

/**
 * 解析后的一"块"内容，按顺序排列就是整篇笔记。游戏里的书本界面只认这些类型，
 * 不需要知道 Markdown 语法细节。
 */
public sealed interface MdBlock {
	/** # 标题，level 1~6。 */
	record Heading(int level, List<MdSpan> spans) implements MdBlock {}

	record Paragraph(List<MdSpan> spans) implements MdBlock {}

	/**
	 * 列表项里的一段文字。
	 * @param depth   嵌套层数，最外层是 0
	 * @param marker  "•"（无序）或 "3."（有序，按真实序号）；同一项的第二段及以后为 null
	 * @param checked 任务列表 - [x] 为 true，- [ ] 为 false；普通列表为 null
	 */
	record ListItem(int depth, String marker, Boolean checked, List<MdSpan> spans) implements MdBlock {}

	/** 引用 > 里的一段文字；depth 是 > 的层数，至少 1。 */
	record Quote(int depth, List<MdSpan> spans) implements MdBlock {}

	/** 代码块；language 没写时为 ""；text 末尾不带换行。 */
	record Code(String language, String text) implements MdBlock {}

	/** 块级公式 $$...$$；tex 是去掉 $$ 后、两端去空白的 TeX 源码。 */
	record Math(String tex) implements MdBlock {}

	/** 分隔线 ---。 */
	record Rule() implements MdBlock {}

	/** 表格：rows.get(0) 是表头；每个单元格是一组 spans。 */
	record Table(List<List<List<MdSpan>>> rows) implements MdBlock {}
}
