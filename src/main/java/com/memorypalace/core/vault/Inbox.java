package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.NoteCipher;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快速收集的收件箱：每天一个文件 "收件箱/yyyy-MM-dd.md"，写下第一条时才创建（不会留下空文件）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class Inbox {
	public static final String FOLDER = "收件箱";

	private Inbox() {}

	// 时区标注，例如 "(UTC+8)"、"(UTC-3:30)"、"(UTC)"；从文件里找最近一条带标注的记录，就知道上一条是哪个时区
	private static final Pattern TAG = Pattern.compile("^- \\d{2}:\\d{2} \\(UTC(?:([+-])(\\d{1,2})(?::(\\d{2}))?)?\\) ");

	/**
	 * 把一条想法记进当天的收件箱。要求：
	 * - now 是记录者所在地的本地时间（带时区偏移）；日期、时刻都按它的本地值
	 * - 文件路径 FOLDER + "/" + now 的本地日期（yyyy-MM-dd）+ ".md"
	 * - text 里的换行（\r、\n）都换成空格，再去掉首尾空白；处理后为空 → IllegalArgumentException
	 * - 每条一行："- HH:mm 内容"（24 小时制）。需要标注时区时为 "- HH:mm (UTC+8) 内容"：
	 *   每个文件的第一条一定标注；之后只有和"文件里最近一条带标注的记录"时区不同时才标注
	 * - 当天文件不存在：用 vault.createNote 新建，正文为 "# 收件箱 yyyy-MM-dd\n\n- HH:mm (UTC+x) 内容\n"
	 * - 当天文件已存在：在正文末尾追加一行（正文不以换行结尾时先补一个 "\n"），用 vault.writeBody 写回，
	 *   expectedHash 用刚读到的正文的 hash；返回 CONFLICT 时重新读一次再试一次，还冲突就抛 IOException
	 * - 当天的收件箱是加密笔记 → IllegalStateException（服务端没有钥匙，不能往里追加）
	 * - 返回这篇收件箱笔记（追加后的 NoteSummary）
	 */
	public static NoteSummary append(Vault vault, OffsetDateTime now, String text) throws IOException {
		String content = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
		if (content.isEmpty()) {
			throw new IllegalArgumentException("收集内容不能为空");
		}
		String date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String path = FOLDER + "/" + date + ".md";
		String time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
		String tagged = "- " + time + " (" + label(now.getOffset()) + ") " + content + "\n";
		String plain = "- " + time + " " + content + "\n";
		synchronized (vault) {
			for (int attempt = 0; attempt < 2; attempt++) {
				// 同步外部编辑的路径和加密标记，重试时也重新检查。
				vault.scan();
				NoteSummary note = vault.resolve(path).orElse(null);
				if (note == null) {
					if (attempt > 0) {
						throw new IOException("收件箱笔记已不存在");
					}
					// 新文件的第一条一定标注时区
					return vault.createNote(path, "# " + FOLDER + " " + date + "\n\n" + tagged);
				}
				if (note.encrypted()) {
					throw new IllegalStateException("不能向加密收件箱追加内容");
				}
				String body = vault.readBody(note.id())
						.orElseThrow(() -> new IOException("无法读取收件箱笔记"));
				if (NoteCipher.isEncrypted(body)) {
					throw new IllegalStateException("不能向加密收件箱追加内容");
				}
				// 和最近一条带标注的记录同一个时区就不标，换了时区（或者文件里一条标注都没有）才标
				String line = now.getOffset().equals(lastOffset(body)) ? plain : tagged;
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

	/** "UTC"、"UTC+8"、"UTC-3:30"、"UTC+5:45"。 */
	static String label(ZoneOffset offset) {
		int total = offset.getTotalSeconds();
		if (total == 0) {
			return "UTC";
		}
		int abs = Math.abs(total);
		int hours = abs / 3600;
		int minutes = abs % 3600 / 60;
		return "UTC" + (total < 0 ? "-" : "+") + hours + (minutes == 0 ? "" : String.format(Locale.ROOT, ":%02d", minutes));
	}

	/** 文件里最近一条带时区标注的记录的时区；一条都没有时为 null。 */
	private static ZoneOffset lastOffset(String body) {
		String[] lines = body.split("\n");
		for (int i = lines.length - 1; i >= 0; i--) {
			Matcher m = TAG.matcher(lines[i].stripTrailing());
			if (m.find()) {
				if (m.group(1) == null) {
					return ZoneOffset.UTC;
				}
				int hours = Integer.parseInt(m.group(2));
				int minutes = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
				int sign = m.group(1).equals("-") ? -1 : 1;
				try {
					return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
				} catch (java.time.DateTimeException e) {
					return null; // 手写的标注不合法（比如 UTC+99）：当作没有
				}
			}
		}
		return null;
	}
}
