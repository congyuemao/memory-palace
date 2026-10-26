package com.memorypalace.client.book;

import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版：把 Markdown 块变成一行一行（BookLine），再按书页高度分页。
 * 颜色、缩进这些"长什么样"的决定都在这里。
 */
public final class BookLayout {
	// 原版书一页的文字区大小
	public static final int VANILLA_TEXT_WIDTH = 114;
	public static final int VANILLA_TEXT_HEIGHT = 128;

	// 原版书的字是纯黑；其他颜色都挑了在羊皮纸上看得清的深色
	public static final int TEXT = 0xFF000000;
	static final int HEADING = 0xFF3B2410;
	public static final int MUTED = 0xFF5A4A3A;
	static final int CODE = 0xFF1F4A2A;
	static final int MATH = 0xFF1A3A7A;
	static final int LINK = 0xFF1A3AA0;

	private static final int INDENT = 8;
	private static final int BLOCK_GAP = 4;

	/** 一行要画的东西。kind 决定额外的装饰（代码底色、引用竖线、分隔线）。 */
	public record BookLine(Kind kind, int x, FormattedCharSequence text, int depth, int height) {}

	public enum Kind { TEXT, CODE, QUOTE, RULE, GAP }

	private final Font font;
	private final int pageWidth;
	private final int pageHeight;
	private final List<BookLine> lines = new ArrayList<>();

	private BookLayout(Font font, int pageWidth, int pageHeight) {
		this.font = font;
		this.pageWidth = pageWidth;
		this.pageHeight = pageHeight;
	}

	/** 排版并分页：文字区宽 pageWidth、高 pageHeight（未缩放的字体像素）。返回的每一项是一页的行，至少一页。 */
	public static List<List<BookLine>> paginate(Font font, List<MdBlock> blocks, int pageWidth, int pageHeight) {
		BookLayout layout = new BookLayout(font, pageWidth, pageHeight);
		for (MdBlock block : blocks) {
			layout.add(block);
		}
		return layout.pages();
	}

	private void add(MdBlock block) {
		switch (block) {
			case MdBlock.Heading h -> {
				if (!lines.isEmpty()) {
					gap(BLOCK_GAP);
				}
				Style style = Style.EMPTY.withColor(HEADING).withBold(true)
						.withUnderlined(h.level() == 1).withItalic(h.level() >= 3);
				wrap(Kind.TEXT, 0, styled(h.spans(), style), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.Paragraph p -> {
				wrap(Kind.TEXT, 0, styled(p.spans(), Style.EMPTY.withColor(TEXT)), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.ListItem item -> {
				int x = item.depth() * INDENT;
				String marker = item.checked() == null ? item.marker()
						: (item.checked() ? "☑" : "☐");
				Style base = Style.EMPTY.withColor(item.checked() == Boolean.TRUE ? MUTED : TEXT)
						.withStrikethrough(item.checked() == Boolean.TRUE ? true : null);
				wrap(Kind.TEXT, x, styled(item.spans(), base), marker, 0);
				gap(1);
			}
			case MdBlock.Quote q -> {
				wrap(Kind.QUOTE, q.depth() * 6, styled(q.spans(), Style.EMPTY.withColor(MUTED).withItalic(true)), null, q.depth());
				gap(BLOCK_GAP);
			}
			case MdBlock.Code c -> {
				Style style = Style.EMPTY.withColor(CODE);
				for (String line : c.text().split("\n", -1)) {
					wrap(Kind.CODE, 3, List.of(Component.literal(line.isEmpty() ? " " : line).withStyle(style)), null, 0);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Math m -> {
				// M4 换成公式图片；现在先用蓝色原样显示
				wrap(Kind.TEXT, 0, List.of(Component.literal(m.tex()).withStyle(Style.EMPTY.withColor(MATH))), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.Image img -> {
				// 临时：显示成文字占位，M4 换成真正的图片
				wrap(Kind.TEXT, 0, List.of(Component.literal("[图片: " + img.src() + "]")
						.withStyle(Style.EMPTY.withColor(MUTED).withItalic(true))), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.Rule r -> {
				lines.add(new BookLine(Kind.RULE, 0, FormattedCharSequence.EMPTY, 0, 7));
				gap(BLOCK_GAP);
			}
			case MdBlock.Table t -> {
				for (int i = 0; i < t.rows().size(); i++) {
					Style style = Style.EMPTY.withColor(TEXT).withBold(i == 0 ? true : null);
					List<MutableComponent> row = new ArrayList<>();
					List<List<MdSpan>> cells = t.rows().get(i);
					for (int c = 0; c < cells.size(); c++) {
						if (c > 0) {
							row.add(Component.literal(" │ ").withStyle(Style.EMPTY.withColor(MUTED)));
						}
						row.addAll(styled(cells.get(c), style));
					}
					wrap(Kind.TEXT, 0, row, null, 0);
				}
				gap(BLOCK_GAP);
			}
		}
	}

	/**
	 * 把一串组件按 "\n" 分成若干逻辑行，再按页宽自动换行。
	 * marker（列表符号）只画在第一行，后面的行和文字对齐（悬挂缩进）。
	 */
	private void wrap(Kind kind, int x, List<MutableComponent> parts, String marker, int depth) {
		int markerWidth = 0;
		if (marker != null) {
			markerWidth = font.width(marker + " ");
		}
		int width = Math.max(20, pageWidth - x - markerWidth);
		boolean first = true;
		for (MutableComponent logical : splitOnNewlines(parts)) {
			List<FormattedCharSequence> wrapped = font.split(logical, width);
			if (wrapped.isEmpty()) {
				wrapped = List.of(FormattedCharSequence.EMPTY);
			}
			for (FormattedCharSequence seq : wrapped) {
				if (first && marker != null) {
					FormattedCharSequence m = Component.literal(marker + " ").withStyle(Style.EMPTY.withColor(MUTED)).getVisualOrderText();
					lines.add(new BookLine(kind, x, FormattedCharSequence.composite(m, seq), depth, font.lineHeight));
				} else {
					lines.add(new BookLine(kind, x + markerWidth, seq, depth, font.lineHeight));
				}
				first = false;
			}
		}
	}

	private static List<MutableComponent> splitOnNewlines(List<MutableComponent> parts) {
		List<MutableComponent> out = new ArrayList<>();
		MutableComponent current = Component.empty();
		for (MutableComponent part : parts) {
			String text = part.getString();
			String[] pieces = text.split("\n", -1);
			for (int i = 0; i < pieces.length; i++) {
				if (i > 0) {
					out.add(current);
					current = Component.empty();
				}
				if (!pieces[i].isEmpty()) {
					current.append(Component.literal(pieces[i]).withStyle(part.getStyle()));
				}
			}
		}
		out.add(current);
		return out;
	}

	/** MdSpan → 带样式的组件。base 是这一块的基础样式（颜色等）。 */
	static List<MutableComponent> styled(List<MdSpan> spans, Style base) {
		List<MutableComponent> out = new ArrayList<>();
		for (MdSpan span : spans) {
			Style style = base;
			if (span.has(MdSpan.BOLD)) style = style.withBold(true);
			if (span.has(MdSpan.ITALIC)) style = style.withItalic(true);
			if (span.has(MdSpan.STRIKE)) style = style.withStrikethrough(true);
			if (span.has(MdSpan.CODE)) style = style.withColor(CODE);
			String text = span.text();
			if (span.has(MdSpan.MATH)) {
				style = style.withColor(MATH);
				text = "$" + text + "$";
			}
			if (span.link() != null) {
				style = style.withColor(LINK).withUnderlined(true);
			}
			out.add(Component.literal(text).withStyle(style));
		}
		return out;
	}

	private void gap(int height) {
		lines.add(new BookLine(Kind.GAP, 0, FormattedCharSequence.EMPTY, 0, height));
	}

	private List<List<BookLine>> pages() {
		List<List<BookLine>> pages = new ArrayList<>();
		List<BookLine> page = new ArrayList<>();
		int used = 0;
		for (BookLine line : lines) {
			// 页首的空隙没有意义，跳过
			if (line.kind() == Kind.GAP && page.isEmpty()) {
				continue;
			}
			if (used + line.height() > pageHeight && !page.isEmpty()) {
				pages.add(page);
				page = new ArrayList<>();
				used = 0;
				if (line.kind() == Kind.GAP) {
					continue;
				}
			}
			page.add(line);
			used += line.height();
		}
		if (!page.isEmpty() || pages.isEmpty()) {
			pages.add(page);
		}
		return pages;
	}
}
