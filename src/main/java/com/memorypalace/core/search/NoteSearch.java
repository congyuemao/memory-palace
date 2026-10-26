package com.memorypalace.core.search;

import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;

import java.util.List;

/**
 * 找笔记：按关键词搜标题、路径和正文。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteSearch {
	private NoteSearch() {}

	/**
	 * 一条结果。
	 * @param snippet 正文里命中的那一小段（单行，前后各约 20 个字，前后被截断时加"…"）；只在标题和路径都没命中、
	 *                靠正文命中时才有，否则为 ""
	 */
	public record Hit(NoteSummary note, int score, String snippet) {}

	/**
	 * 要求：
	 * - query 按空白切成若干关键词；每个关键词都要命中（标题、路径、正文任一处）才算结果（AND）
	 * - 不区分大小写（Locale.ROOT）
	 * - 打分：每个关键词命中标题 +100、只命中路径 +50、只命中正文 +10；标题和 query（去掉首尾空白）完全相同再 +1000
	 * - 加密笔记（note.encrypted()）只搜标题和路径，绝不读取正文（正文是密文，也不能把密文当片段返回）
	 * - 正文用 vault.readBody 读；读失败（IOException 或 empty）就当正文为空，不抛异常
	 * - 按分数从高到低，同分按 relativePath 字典序；最多返回 limit 条
	 * - query 为 null、空或全是空白 → 空列表
	 */
	public static List<Hit> search(Vault vault, String query, int limit) {
		throw new UnsupportedOperationException("TODO");
	}
}
