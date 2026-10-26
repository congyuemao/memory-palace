package com.memorypalace.client.book;

import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版：把 Markdown 块变成一行一行（BookLine），再按书页高度分页。
 * 一行由若干"段"组成：文字段，或者图块（公式、图片）。图块和文字按基线对齐。
 * 颜色、缩进这些"长什么样"的决定都在这里。
 *
 * 单位：排版单位 = 未放大的字体像素（画的时候整页再乘字体大小）。
 */
public final class BookLayout {
	// 原版书的字是纯黑；其他颜色都挑了在羊皮纸上看得清的深色
	public static final int TEXT = 0xFF000000;
	static final int HEADING = 0xFF3B2410;
	public static final int MUTED = 0xFF5A4A3A;
	static final int CODE = 0xFF1F4A2A;
	public static final int MATH = 0xFF1A3A7A;
	static final int LINK = 0xFF1A3AA0;
	static final int ERROR = 0xFFA02020;

	private static final int INDENT = 8;
	private static final int BLOCK_GAP = 4;
	// 原版字体：一行 9 像素，基线在顶部往下 7 像素
	private static final int FONT_ASCENT = 7;
	private static final int FONT_DESCENT = 2;

	/**
	 * 一个图块（公式或图片）对应的贴图。贴图按"屏幕实际像素"生成，pxPerUnit 是 1 个排版单位对应多少贴图像素，
	 * 这样画出来 1 个贴图像素 = 1 个屏幕像素，不会糊。
	 */
	public record Box(Identifier texture, int rasterW, int rasterH, int rasterDepth, float pxPerUnit) {
		float width() { return rasterW / pxPerUnit; }
		float height() { return rasterH / pxPerUnit; }
		float depth() { return rasterDepth / pxPerUnit; }
	}

	/** 排版器向外要图块用的接口（由笔记窗口实现：负责生成贴图、请求图片）。 */
	public interface Boxes {
		/** 公式图块；TeX 写错了返回 null（会显示红色原文）。 */
		Box math(String tex, boolean display);

		/** 图片图块；还没加载好或加载失败返回 null，这时显示 imagePlaceholder 的文字。 */
		Box image(String src);

		String imagePlaceholder(String src, String alt);
	}

	/** 一行里的一段。x、y 是相对行左上角的位置。 */
	public sealed interface Seg {}

	public record TextSeg(int x, int y, FormattedCharSequence text) implements Seg {}

	/** scale：图块太宽放不下时的缩小比例（1 = 原大小）。 */
	public record BoxSeg(float x, float y, Box box, float scale) implements Seg {}

	/** 一行。kind 决定额外的装饰（代码底色、引用竖线、分隔线）。 */
	public record BookLine(Kind kind, int depth, int height, List<Seg> segs) {}

	public enum Kind { TEXT, CODE, QUOTE, RULE, GAP }

	// 排版时的"零件"：一段同样式的文字，或一个图块
	private sealed interface Piece {}

	private record TextPiece(String text, Style style) implements Piece {}

	private record BoxPiece(Box box) implements Piece {}

	private final Font font;
	private final StringSplitter splitter;
	private final int pageWidth;
	private final int pageHeight;
	private final Boxes boxes;
	private final List<BookLine> lines = new ArrayList<>();

	private BookLayout(Font font, int pageWidth, int pageHeight, Boxes boxes) {
		this.font = font;
		this.splitter = font.getSplitter();
		this.pageWidth = pageWidth;
		this.pageHeight = pageHeight;
		this.boxes = boxes;
	}

	/** 排版并分页：文字区宽 pageWidth、高 pageHeight（排版单位）。返回的每一项是一页的行，至少一页。 */
	public static List<List<BookLine>> paginate(Font font, List<MdBlock> blocks, int pageWidth, int pageHeight, Boxes boxes) {
		BookLayout layout = new BookLayout(font, pageWidth, pageHeight, boxes);
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
				flow(Kind.TEXT, 0, pieces(h.spans(), style), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.Paragraph p -> {
				flow(Kind.TEXT, 0, pieces(p.spans(), Style.EMPTY.withColor(TEXT)), null, 0);
				gap(BLOCK_GAP);
			}
			case MdBlock.ListItem item -> {
				int x = item.depth() * INDENT;
				String marker = item.checked() == null ? item.marker()
						: (item.checked() ? "☑" : "☐");
				Style base = Style.EMPTY.withColor(item.checked() == Boolean.TRUE ? MUTED : TEXT)
						.withStrikethrough(item.checked() == Boolean.TRUE ? true : null);
				flow(Kind.TEXT, x, pieces(item.spans(), base), marker, 0);
				gap(1);
			}
			case MdBlock.Quote q -> {
				flow(Kind.QUOTE, q.depth() * 6, pieces(q.spans(), Style.EMPTY.withColor(MUTED).withItalic(true)), null, q.depth());
				gap(BLOCK_GAP);
			}
			case MdBlock.Code c -> {
				Style style = Style.EMPTY.withColor(CODE);
				for (String line : c.text().split("\n", -1)) {
					flow(Kind.CODE, 3, List.of(new TextPiece(line.isEmpty() ? " " : line, style)), null, 0);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Math m -> {
				Box box = boxes.math(m.tex(), true);
				if (box == null) {
					flow(Kind.TEXT, 0, List.of(new TextPiece("$$" + m.tex() + "$$", Style.EMPTY.withColor(ERROR))), null, 0);
				} else {
					centeredBox(box, false);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Image img -> {
				Box box = boxes.image(img.src());
				if (box == null) {
					flow(Kind.TEXT, 0, List.of(new TextPiece(boxes.imagePlaceholder(img.src(), img.alt()),
							Style.EMPTY.withColor(MUTED).withItalic(true))), null, 0);
				} else {
					// 图片铺满页宽（小图也放大），太高就按页高缩
					centeredBox(box, true);
				}
				gap(BLOCK_GAP);
			}
			case MdBlock.Rule r -> {
				lines.add(new BookLine(Kind.RULE, 0, 7, List.of()));
				gap(BLOCK_GAP);
			}
			case MdBlock.Table t -> {
				for (int i = 0; i < t.rows().size(); i++) {
					Style style = Style.EMPTY.withColor(TEXT).withBold(i == 0 ? true : null);
					List<Piece> row = new ArrayList<>();
					List<List<MdSpan>> cells = t.rows().get(i);
					for (int c = 0; c < cells.size(); c++) {
						if (c > 0) {
							row.add(new TextPiece(" │ ", Style.EMPTY.withColor(MUTED)));
						}
						row.addAll(pieces(cells.get(c), style));
					}
					flow(Kind.TEXT, 0, row, null, 0);
				}
				gap(BLOCK_GAP);
			}
		}
	}

	/**
	 * 单独一行、居中的图块（块级公式、图片）。太宽就按页宽缩小，太高就按页高缩小。
	 * fillWidth = true（图片）时小的也放大到页宽。
	 */
	private void centeredBox(Box box, boolean fillWidth) {
		float fit = Math.min(pageWidth / box.width(), (pageHeight - 2) / box.height());
		float scale = fillWidth ? fit : Math.min(1.0F, fit);
		float w = box.width() * scale;
		int height = (int) Math.ceil(box.height() * scale);
		lines.add(new BookLine(Kind.TEXT, 0, height, List.of(new BoxSeg((pageWidth - w) / 2, 0, box, scale))));
	}

	// ---- 自动换行 ----

	/** 一行正在拼的内容。 */
	private final class LineBuilder {
		final Kind kind;
		final int depth;
		final List<Object[]> items = new ArrayList<>(); // {x, Piece-或-Box, 文字}
		float x;
		float ascent = FONT_ASCENT;
		float descent = FONT_DESCENT;

		LineBuilder(Kind kind, int depth, float startX) {
			this.kind = kind;
			this.depth = depth;
			this.x = startX;
		}

		void addText(String text, Style style) {
			items.add(new Object[]{x, style, text});
			x += font.width(FormattedText.of(text, style));
		}

		void addBox(Box box, float scale) {
			items.add(new Object[]{x, box, scale});
			x += box.width() * scale;
			ascent = Math.max(ascent, (box.height() - box.depth()) * scale);
			descent = Math.max(descent, box.depth() * scale);
		}

		void finish() {
			int a = (int) Math.ceil(ascent);
			int height = a + (int) Math.ceil(descent);
			List<Seg> segs = new ArrayList<>();
			for (Object[] it : items) {
				float ix = (float) it[0];
				if (it[1] instanceof Style style) {
					FormattedCharSequence seq = Component.literal((String) it[2]).withStyle(style).getVisualOrderText();
					segs.add(new TextSeg(Math.round(ix), a - FONT_ASCENT, seq));
				} else {
					Box box = (Box) it[1];
					float scale = (float) it[2];
					segs.add(new BoxSeg(ix, a - (box.height() - box.depth()) * scale, box, scale));
				}
			}
			lines.add(new BookLine(kind, depth, Math.max(height, font.lineHeight), segs));
		}
	}

	/**
	 * 把一串零件按页宽排成若干行。"\n" 强制换行；marker（列表符号）只画在第一行，后面的行和文字对齐（悬挂缩进）。
	 */
	private void flow(Kind kind, int x, List<Piece> pieces, String marker, int depth) {
		float indent = x;
		LineBuilder line = new LineBuilder(kind, depth, x);
		if (marker != null) {
			Style ms = Style.EMPTY.withColor(MUTED);
			line.addText(marker + " ", ms);
			indent = line.x;
		}
		for (Piece piece : pieces) {
			switch (piece) {
				case BoxPiece bp -> {
					float available = pageWidth - indent;
					float scale = Math.min(1.0F, available / bp.box().width());
					if (line.x > indent && line.x + bp.box().width() * scale > pageWidth) {
						line.finish();
						line = new LineBuilder(kind, depth, indent);
					}
					line.addBox(bp.box(), scale);
				}
				case TextPiece tp -> {
					String[] logical = tp.text().split("\n", -1);
					for (int i = 0; i < logical.length; i++) {
						if (i > 0) {
							line.finish();
							line = new LineBuilder(kind, depth, indent);
						}
						line = flowText(line, logical[i], tp.style(), kind, depth, indent);
					}
				}
			}
		}
		line.finish();
	}

	/** 把一段文字塞进当前行，放不下就按词换行，返回最后那一行（可能还没满）。 */
	private LineBuilder flowText(LineBuilder line, String text, Style style, Kind kind, int depth, float indent) {
		String rest = text;
		while (!rest.isEmpty()) {
			int available = (int) Math.floor(pageWidth - line.x);
			int cut = available <= 0 ? 0 : splitter.findLineBreak(rest, available, style);
			if (cut < rest.length() && line.x > indent && cut > 0 && splitsWord(rest, cut)) {
				// 行中间放不下一个完整的英文单词：整个单词挪到下一行，别从中间切开
				cut = 0;
			}
			if (cut == 0) {
				if (line.x > indent) {
					line.finish();
					line = new LineBuilder(kind, depth, indent);
					rest = rest.stripLeading();
					continue;
				}
				// 一整行都放不下一个字符（页面极窄）：硬塞一个，避免死循环
				cut = Math.max(1, splitter.plainIndexAtWidth(rest, Math.max(1, available), style));
			}
			line.addText(rest.substring(0, cut), style);
			rest = rest.substring(cut);
			if (!rest.isEmpty()) {
				line.finish();
				line = new LineBuilder(kind, depth, indent);
				rest = rest.stripLeading();
			}
		}
		return line;
	}

	/** 在 cut 处切开是不是把一个英文单词/数字切成了两半。 */
	private static boolean splitsWord(String s, int cut) {
		char before = s.charAt(cut - 1);
		char after = s.charAt(cut);
		return isWordChar(before) && isWordChar(after) && s.substring(0, cut).chars().noneMatch(Character::isWhitespace);
	}

	private static boolean isWordChar(char c) {
		return c < 0x2E80 && Character.isLetterOrDigit(c);
	}

	/** MdSpan → 排版零件。base 是这一块的基础样式（颜色等）。行内公式变成图块，写错了显示红色原文。 */
	private List<Piece> pieces(List<MdSpan> spans, Style base) {
		List<Piece> out = new ArrayList<>();
		for (MdSpan span : spans) {
			if (span.has(MdSpan.MATH)) {
				Box box = boxes.math(span.text(), false);
				if (box != null) {
					out.add(new BoxPiece(box));
				} else {
					out.add(new TextPiece("$" + span.text() + "$", base.withColor(ERROR)));
				}
				continue;
			}
			Style style = base;
			if (span.has(MdSpan.BOLD)) style = style.withBold(true);
			if (span.has(MdSpan.ITALIC)) style = style.withItalic(true);
			if (span.has(MdSpan.STRIKE)) style = style.withStrikethrough(true);
			if (span.has(MdSpan.CODE)) style = style.withColor(CODE);
			if (span.link() != null) {
				style = style.withColor(LINK).withUnderlined(true);
			}
			out.add(new TextPiece(span.text(), style));
		}
		return out;
	}

	private void gap(int height) {
		lines.add(new BookLine(Kind.GAP, 0, height, List.of()));
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
