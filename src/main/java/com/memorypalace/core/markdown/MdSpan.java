package com.memorypalace.core.markdown;

/**
 * 一段样式相同的文字。text 里可能含 "\n"（Markdown 里的换行要原样保留成换行）。
 * @param styles 下面这些标志按位或
 * @param link   链接地址；不是链接时为 null
 */
public record MdSpan(String text, int styles, String link) {
	public static final int BOLD = 1;
	public static final int ITALIC = 1 << 1;
	public static final int CODE = 1 << 2;
	public static final int STRIKE = 1 << 3;
	/** 行内公式 $...$，text 是去掉两边 $ 的 TeX 源码（M4 渲染成图片，之前原样显示）。 */
	public static final int MATH = 1 << 4;

	public static MdSpan plain(String text) {
		return new MdSpan(text, 0, null);
	}

	public boolean has(int style) {
		return (styles & style) != 0;
	}
}
