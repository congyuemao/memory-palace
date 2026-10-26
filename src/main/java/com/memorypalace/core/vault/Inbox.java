package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.NoteCipher;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 快速收集的收件箱：每天一个文件 "收件箱/yyyy-MM-dd.md"，写下第一条时才创建（不会留下空文件）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class Inbox {
	public static final String FOLDER = "收件箱";

	private Inbox() {}

	/**
	 * 把一条想法记进当天的收件箱。要求：
	 * - 文件路径 FOLDER + "/" + now 的日期（yyyy-MM-dd）+ ".md"
	 * - text 里的换行（\r、\n）都换成空格，再去掉首尾空白；处理后为空 → IllegalArgumentException
	 * - 每条一行："- HH:mm 内容"（24 小时制，时间取 now）
	 * - 当天文件不存在：用 vault.createNote 新建，正文为 "# 收件箱 yyyy-MM-dd\n\n- HH:mm 内容\n"
	 * - 当天文件已存在：在正文末尾追加一行（正文不以换行结尾时先补一个 "\n"），用 vault.writeBody 写回，
	 *   expectedHash 用刚读到的正文的 hash；返回 CONFLICT 时重新读一次再试一次，还冲突就抛 IOException
	 * - 当天的收件箱是加密笔记 → IllegalStateException（服务端没有钥匙，不能往里追加）
	 * - 返回这篇收件箱笔记（追加后的 NoteSummary）
	 */
	public static NoteSummary append(Vault vault, LocalDateTime now, String text) throws IOException {
		String content = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
		if (content.isEmpty()) {
			throw new IllegalArgumentException("收集内容不能为空");
		}
		String date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String path = FOLDER + "/" + date + ".md";
		String line = "- " + now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)) + " " + content + "\n";
		synchronized (vault) {
			for (int attempt = 0; attempt < 2; attempt++) {
				// 同步外部编辑的路径和加密标记，重试时也重新检查。
				vault.scan();
				NoteSummary note = vault.resolve(path).orElse(null);
				if (note == null) {
					if (attempt > 0) {
						throw new IOException("收件箱笔记已不存在");
					}
					return vault.createNote(path, "# " + FOLDER + " " + date + "\n\n" + line);
				}
				if (note.encrypted()) {
					throw new IllegalStateException("不能向加密收件箱追加内容");
				}
				String body = vault.readBody(note.id())
						.orElseThrow(() -> new IOException("无法读取收件箱笔记"));
				if (NoteCipher.isEncrypted(body)) {
					throw new IllegalStateException("不能向加密收件箱追加内容");
				}
				String updated = body + (body.endsWith("\n") ? "" : "\n") + line;
				Vault.WriteResult result = vault.writeBody(note.id(), updated, Vault.hash(body));
				if (result == Vault.WriteResult.OK) {
					return vault.find(note.id()).orElseThrow(() -> new IOException("无法找到追加后的收件箱笔记"));
				}
				if (result == Vault.WriteResult.MISSING) {
					throw new IOException("收件箱笔记已不存在");
				}
			}
			throw new IOException("收件箱笔记连续发生写入冲突");
		}
	}
}
