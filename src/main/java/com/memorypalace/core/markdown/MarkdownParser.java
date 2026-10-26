package com.memorypalace.core.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;

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
		if (markdown == null || markdown.isEmpty()) {
			return List.of();
		}
		var blocks = new ArrayList<MdBlock>();
		try {
			var parser = Parser.builder()
					.extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(),
							TaskListItemsExtension.create()))
					.includeSourceSpans(IncludeSourceSpans.BLOCKS)
					.customInlineContentParserFactory(new MathParserFactory())
					.build();
			new BlockReader(markdown, blocks).read(parser.parse(markdown), 0, 0, null);
			return List.copyOf(blocks);
		} catch (Throwable ignored) {
			// 深度嵌套导致的栈溢出也降级，保留已经转换的块。
			return blocks.isEmpty()
					? List.of(new MdBlock.Paragraph(List.of(MdSpan.plain(markdown))))
					: blocks;
		}
	}

	private static final class BlockReader {
		private final String source;
		private final List<MdBlock> blocks;

		private BlockReader(String source, List<MdBlock> blocks) {
			this.source = source;
			this.blocks = blocks;
		}

		private void read(Node node, int listDepth, int quoteDepth, ItemState item) {
			if (node instanceof Paragraph) {
				String raw = sourceText(node).strip();
				if (raw.length() >= 4 && raw.startsWith("$$") && raw.endsWith("$$")) {
					blocks.add(new MdBlock.Math(raw.substring(2, raw.length() - 2).strip()));
					if (item != null) {
						item.first = false;
					}
				} else {
					addParagraph(spans(node), quoteDepth, item);
				}
			} else if (node instanceof Heading heading) {
				blocks.add(new MdBlock.Heading(heading.getLevel(), spans(node)));
			} else if (node instanceof FencedCodeBlock code) {
				String info = code.getInfo().strip();
				String language = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
				blocks.add(new MdBlock.Code(language, withoutFinalNewlines(code.getLiteral())));
			} else if (node instanceof IndentedCodeBlock code) {
				blocks.add(new MdBlock.Code("", withoutFinalNewlines(code.getLiteral())));
			} else if (node instanceof ThematicBreak) {
				blocks.add(new MdBlock.Rule());
			} else if (node instanceof HtmlBlock html) {
				addParagraph(List.of(MdSpan.plain(html.getLiteral())), quoteDepth, item);
			} else if (node instanceof TableBlock) {
				var rows = new ArrayList<List<List<MdSpan>>>();
				for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
					for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
						var cells = new ArrayList<List<MdSpan>>();
						for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
							cells.add(spans(cell));
						}
						rows.add(List.copyOf(cells));
					}
				}
				blocks.add(new MdBlock.Table(List.copyOf(rows)));
			} else if (node instanceof ListBlock) {
				long number = node instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : 0;
				for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
					String marker = node instanceof OrderedList ? number++ + "." : "•";
					Boolean checked = child.getFirstChild() instanceof TaskListItemMarker task
							? task.isChecked() : null;
					var state = new ItemState(listDepth, marker, checked);
					if (child.getFirstChild() == null) {
						addParagraph(List.of(), quoteDepth, state);
					} else {
						readChildren(child, listDepth + 1, quoteDepth, state);
					}
				}
			} else if (node instanceof BlockQuote) {
				readChildren(node, listDepth, quoteDepth + 1, null);
			} else {
				readChildren(node, listDepth, quoteDepth, item);
			}
		}

		private void readChildren(Node node, int listDepth, int quoteDepth, ItemState item) {
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
				read(child, listDepth, quoteDepth, item);
			}
		}

		private void addParagraph(List<MdSpan> spans, int quoteDepth, ItemState item) {
			if (item != null) {
				blocks.add(new MdBlock.ListItem(item.depth, item.first ? item.marker : null,
						item.first ? item.checked : null, spans));
				item.first = false;
			} else if (quoteDepth > 0) {
				blocks.add(new MdBlock.Quote(quoteDepth, spans));
			} else {
				blocks.add(new MdBlock.Paragraph(spans));
			}
		}

		private String sourceText(Node node) {
			// 使用原文，避免 TeX 的反斜杠、下划线等被 Markdown 消耗。
			var text = new StringBuilder();
			for (SourceSpan span : node.getSourceSpans()) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(source, span.getInputIndex(), span.getInputIndex() + span.getLength());
			}
			return text.toString();
		}
	}

	private static final class ItemState {
		private final int depth;
		private final String marker;
		private final Boolean checked;
		private boolean first = true;

		private ItemState(int depth, String marker, Boolean checked) {
			this.depth = depth;
			this.marker = marker;
			this.checked = checked;
		}
	}

	private static String withoutFinalNewlines(String text) {
		int end = text.length();
		while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
			end--;
		}
		return text.substring(0, end);
	}

	private static List<MdSpan> spans(Node parent) {
		var result = new ArrayList<MdSpan>();
		readInlines(parent, 0, null, result);
		return List.copyOf(result);
	}

	private static void readInlines(Node parent, int styles, String link, List<MdSpan> result) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
			if (node instanceof Text text) {
				appendSpan(result, text.getLiteral(), styles, link);
			} else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
				appendSpan(result, "\n", styles, link);
			} else if (node instanceof Code code) {
				appendSpan(result, code.getLiteral(), styles | MdSpan.CODE, link);
			} else if (node instanceof MathInline math) {
				appendSpan(result, math.tex, styles | MdSpan.MATH, link);
			} else if (node instanceof HtmlInline html) {
				appendSpan(result, html.getLiteral(), styles, link);
			} else if (node instanceof Image) {
				var alt = new StringBuilder();
				for (MdSpan span : spans(node)) {
					alt.append(span.text());
				}
				appendSpan(result, alt.isEmpty() ? "[图片]" : "[图片: " + alt + "]",
						styles | MdSpan.ITALIC, link);
			} else {
				int nestedStyles = styles;
				if (node instanceof StrongEmphasis) {
					nestedStyles |= MdSpan.BOLD;
				} else if (node instanceof Emphasis) {
					nestedStyles |= MdSpan.ITALIC;
				} else if (node instanceof Strikethrough) {
					nestedStyles |= MdSpan.STRIKE;
				}
				readInlines(node, nestedStyles, node instanceof Link target ? target.getDestination() : link, result);
			}
		}
	}

	private static void appendSpan(List<MdSpan> result, String text, int styles, String link) {
		if (text.isEmpty()) {
			return;
		}
		if (!result.isEmpty()) {
			MdSpan previous = result.getLast();
			if (previous.styles() == styles && Objects.equals(previous.link(), link)) {
				result.set(result.size() - 1, new MdSpan(previous.text() + text, styles, link));
				return;
			}
		}
		result.add(new MdSpan(text, styles, link));
	}

	private static final class MathInline extends CustomNode {
		private final String tex;

		private MathInline(String tex) {
			this.tex = tex;
		}
	}

	private static final class MathParserFactory implements InlineContentParserFactory {
		@Override
		public Set<Character> getTriggerCharacters() {
			return Set.of('$');
		}

		@Override
		public InlineContentParser create() {
			return MathParserFactory::parseMath;
		}

		private static ParsedInline parseMath(InlineParserState state) {
			// commonmark 已处理反斜杠转义和代码，这里只会遇到未转义的 $。
			var scanner = state.scanner();
			scanner.next();
			var start = scanner.position();
			if (!scanner.hasNext() || scanner.peek() == '$' || Character.isWhitespace(scanner.peek())) {
				return ParsedInline.none();
			}
			boolean escaped = false;
			while (scanner.hasNext() && scanner.peek() != '\n') {
				char ch = scanner.peek();
				if (ch == '$' && !escaped) {
					if (Character.isWhitespace(scanner.peekPreviousCodePoint())) {
						return ParsedInline.none();
					}
					String tex = scanner.getSource(start, scanner.position()).getContent();
					scanner.next();
					return ParsedInline.of(new MathInline(tex), scanner.position());
				}
				escaped = ch == '\\' && !escaped;
				scanner.next();
			}
			return ParsedInline.none();
		}
	}
}
