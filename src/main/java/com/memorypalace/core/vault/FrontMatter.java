package com.memorypalace.core.vault;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 笔记开头的 "front matter"：文件第一行是 ---，到下一个 --- 为止，中间是 key: value。
 * <pre>
 * ---
 * palace-id: 7f3a2c1e-...
 * tags: [线代, 期中]
 * ---
 * # 正文
 * </pre>
 * 只支持最简单的 "key: value" 单行格式，不是完整的 YAML 解析器。
 * 注意：本包（core/）不允许 import 任何 net.minecraft 的类。
 */
public final class FrontMatter {
	public static final String ID_KEY = "palace-id";

	private FrontMatter() {}

	/**
	 * 解析结果。
	 * @param fields 按出现顺序的字段（LinkedHashMap）；没有 front matter 时为空
	 * @param body   front matter 之后的正文；没有 front matter 时就是整个文本
	 */
	public record Parsed(Map<String, String> fields, String body) {
		/** palace-id 字段；缺失或不是合法 UUID 时返回 null。 */
		public UUID id() {
			String value = fields.get(ID_KEY);
			if (value == null) {
				return null;
			}
			try {
				UUID id = UUID.fromString(value);
				// UUID.fromString 也接受缩写，这里只接受完整格式。
				return id.toString().equalsIgnoreCase(value) ? id : null;
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/**
	 * 解析文本。要求：
	 * - 忽略开头的 UTF-8 BOM；同时支持 \n 和 \r\n 换行
	 * - 第一行必须正好是 "---"（允许行尾空白）才算有 front matter；没有闭合的 --- 则视为没有 front matter
	 * - 值两端空白去掉；值两端成对的单/双引号去掉
	 * - 不认识的行（没有冒号）忽略
	 */
	public static Parsed parse(String text) {
		text = withoutBom(text);
		Map<String, String> fields = new LinkedHashMap<>();
		int closing = closingDelimiter(text);
		if (closing < 0) {
			return new Parsed(fields, text);
		}
		for (int start = nextLine(text, 0); start < closing; start = nextLine(text, start)) {
			String line = text.substring(start, lineEnd(text, start));
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String key = line.substring(0, colon).strip();
			String value = line.substring(colon + 1).strip();
			if (value.length() >= 2) {
				char first = value.charAt(0);
				if ((first == '\'' || first == '"') && value.charAt(value.length() - 1) == first) {
					value = value.substring(1, value.length() - 1);
				}
			}
			fields.put(key, value);
		}
		return new Parsed(fields, text.substring(nextLine(text, closing)));
	}

	/**
	 * 返回把 palace-id 设为 id 之后的完整文本：
	 * - 已有 front matter：替换已有 palace-id 那一行，或在 front matter 末尾追加一行；其他行原样保留
	 * - 没有 front matter：在最前面加上 "---\npalace-id: <id>\n---\n"
	 * - 保留原文的换行风格（原文用 \r\n 就用 \r\n）；BOM 去掉
	 * - 正文一个字节都不改
	 */
	public static String withId(String text, UUID id) {
		text = withoutBom(text);
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		String field = ID_KEY + ": " + id;
		int closing = closingDelimiter(text);
		if (closing < 0) {
			return "---" + newline + field + newline + "---" + newline + text;
		}
		int start = nextLine(text, 0);
		StringBuilder result = new StringBuilder(text.substring(0, start));
		boolean replaced = false;
		while (start < closing) {
			int end = lineEnd(text, start);
			int next = nextLine(text, start);
			String line = text.substring(start, end);
			int colon = line.indexOf(':');
			if (colon >= 0 && line.substring(0, colon).strip().equals(ID_KEY)) {
				result.append(field).append(text, end, next);
				replaced = true;
			} else {
				result.append(text, start, next);
			}
			start = next;
		}
		if (!replaced) {
			result.append(field).append(newline);
		}
		return result.append(text, closing, text.length()).toString();
	}

	/**
	 * 返回把字段 key 设为 value 之后的完整文本（value 为 null = 删掉这个字段）：
	 * - 已有这个字段：替换第一行，同名的其余行删掉；没有就加在 front matter 末尾
	 * - 没有 front matter：value 不为 null 时在最前面加一个；为 null 时原样返回
	 * - 其他行、正文一个字节都不改；保留原文的换行风格；BOM 去掉
	 */
	public static String withField(String text, String key, String value) {
		text = withoutBom(text);
		String newline = text.contains("\r\n") ? "\r\n" : "\n";
		int closing = closingDelimiter(text);
		if (closing < 0) {
			return value == null ? text : "---" + newline + key + ": " + value + newline + "---" + newline + text;
		}
		int start = nextLine(text, 0);
		StringBuilder result = new StringBuilder(text.substring(0, start));
		boolean written = false;
		while (start < closing) {
			int end = lineEnd(text, start);
			int next = nextLine(text, start);
			String line = text.substring(start, end);
			int colon = line.indexOf(':');
			if (colon >= 0 && line.substring(0, colon).strip().equals(key)) {
				if (value != null && !written) {
					result.append(key).append(": ").append(value).append(text, end, next);
					written = true;
				}
			} else {
				result.append(text, start, next);
			}
			start = next;
		}
		if (value != null && !written) {
			result.append(key).append(": ").append(value).append(newline);
		}
		return result.append(text, closing, text.length()).toString();
	}

	private static String withoutBom(String text) {
		return text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	private static int closingDelimiter(String text) {
		if (!text.substring(0, lineEnd(text, 0)).stripTrailing().equals("---")) {
			return -1;
		}
		for (int start = nextLine(text, 0); start < text.length(); start = nextLine(text, start)) {
			if (text.substring(start, lineEnd(text, start)).stripTrailing().equals("---")) {
				return start;
			}
		}
		return -1;
	}

	private static int lineEnd(String text, int start) {
		int end = text.indexOf('\n', start);
		if (end < 0) {
			return text.length();
		}
		return end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
	}

	private static int nextLine(String text, int start) {
		int end = text.indexOf('\n', start);
		return end < 0 ? text.length() : end + 1;
	}
}
