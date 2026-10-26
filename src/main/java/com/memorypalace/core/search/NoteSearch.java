package com.memorypalace.core.search;

import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
		if (query == null || query.isBlank() || limit <= 0) {
			return List.of();
		}
		String normalized = query.strip().toLowerCase(Locale.ROOT);
		String[] terms = normalized.split("\\p{javaWhitespace}+");
		List<Hit> hits = new ArrayList<>();
		for (NoteSummary note : vault.list()) {
			String title = note.title().toLowerCase(Locale.ROOT);
			String path = note.relativePath().toLowerCase(Locale.ROOT);
			String body = null;
			String foldedBody = "";
			String bodyTerm = null;
			boolean metadataMatch = false;
			boolean matches = true;
			int score = title.equals(normalized) ? 1000 : 0;
			for (String term : terms) {
				if (title.contains(term)) {
					score += 100;
					metadataMatch = true;
				} else if (path.contains(term)) {
					score += 50;
					metadataMatch = true;
				} else {
					if (body == null) {
						body = readSearchableBody(vault, note);
						foldedBody = body.toLowerCase(Locale.ROOT);
					}
					if (!foldedBody.contains(term)) {
						matches = false;
						break;
					}
					score += 10;
					if (bodyTerm == null) {
						bodyTerm = term;
					}
				}
			}
			if (matches) {
				hits.add(new Hit(note, score, metadataMatch ? "" : snippet(body, bodyTerm)));
			}
		}
		return hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed()
				.thenComparing(hit -> hit.note().relativePath())).limit(limit).toList();
	}

	private static String readSearchableBody(Vault vault, NoteSummary note) {
		synchronized (vault) {
			if (note.encrypted() || vault.find(note.id()).map(NoteSummary::encrypted).orElse(true)) {
				return "";
			}
			try {
				String body = vault.readBody(note.id()).orElse("");
				// 外部编辑可能让索引过期，密文仍不能参与匹配或片段生成。
				return NoteCipher.isEncrypted(body) ? "" : body;
			} catch (IOException e) {
				return "";
			}
		}
	}

	private static String snippet(String body, String term) {
		int match = body.toLowerCase(Locale.ROOT).indexOf(term);
		int start = originalOffset(body, match);
		int end = originalOffset(body, match + term.length());
		start = body.offsetByCodePoints(start, -Math.min(20, body.codePointCount(0, start)));
		end = body.offsetByCodePoints(end, Math.min(20, body.codePointCount(end, body.length())));
		return (start > 0 ? "…" : "") + body.substring(start, end).replaceAll("\\R", " ")
				+ (end < body.length() ? "…" : "");
	}

	private static int originalOffset(String text, int foldedOffset) {
		// 小写转换可能增加字符数（例如 İ），片段仍使用原文的位置。
		int offset = 0;
		int folded = 0;
		while (offset < text.length() && folded < foldedOffset) {
			int next = text.offsetByCodePoints(offset, 1);
			folded += text.substring(offset, next).toLowerCase(Locale.ROOT).length();
			offset = next;
		}
		return offset;
	}
}
