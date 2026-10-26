package com.memorypalace.core.vault;

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
			throw new UnsupportedOperationException("TODO");
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
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * 返回把 palace-id 设为 id 之后的完整文本：
	 * - 已有 front matter：替换已有 palace-id 那一行，或在 front matter 末尾追加一行；其他行原样保留
	 * - 没有 front matter：在最前面加上 "---\npalace-id: <id>\n---\n"
	 * - 保留原文的换行风格（原文用 \r\n 就用 \r\n）；BOM 去掉
	 * - 正文一个字节都不改
	 */
	public static String withId(String text, UUID id) {
		throw new UnsupportedOperationException("TODO");
	}
}
