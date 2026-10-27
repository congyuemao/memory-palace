package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快速收集的收件箱：每天一个文件，写下第一条时才创建（不会留下空文件）。
 * - 本机房主（owner = null）："收件箱/yyyy-MM-dd.md"，没有作者（无主笔记归房主）
 * - 其他玩家："players/&lt;UUID&gt;/收件箱/yyyy-MM-dd.md"，作者是他自己
 * 收件箱永远只有作者能读（见 NoteSummary.inbox）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class Inbox {
	public static final String FOLDER = "收件箱";
	/** 一天的收件箱最多这么多字符（约 50 万字，正常一天根本记不满）；满了就拒绝继续追加，防止被刷爆磁盘。 */
	public static final int MAX_CHARS = 500_000;

	/** 当天的收件箱已经满了。 */
	public static final class FullException extends IOException {
		public FullException() {
			super("收件箱已满");
		}
	}

	/** 当天的收件箱文件已经存在，但作者不是本人（别人在你的个人区里抢先建了同名文件）。 */
	public static final class NotYoursException extends IllegalStateException {
		public NotYoursException() {
			super("收件箱的作者不是本人");
		}
	}

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
	 * - 当天的收件箱已经变成加密笔记（比如整理过、存成了带锁笔记）：服务端没有钥匙，不能往里追加，
	 *   改记到当天的续篇 "yyyy-MM-dd-2.md"、"-3.md"……（最多 MAX_PARTS 篇，都加密了 → IllegalStateException）
	 * - 当天的收件箱正文已达 MAX_CHARS → FullException，不追加
	 * - 返回这篇收件箱笔记（追加后的 NoteSummary）
	 */
	public static NoteSummary append(Vault vault, OffsetDateTime now, String text) throws IOException {
		return append(vault, null, now, text);
	}

	/**
	 * 同上，记进 owner 的收件箱（owner 为 null = 本机房主的收件箱）。
	 * 当天的文件已经存在、作者却不是 owner（比如管理员在别人的个人区里抢先建了同名文件）→ NotYoursException，
	 * 不往里写：否则这个人记下的想法会落进别人能读的笔记里。
	 */
	public static NoteSummary append(Vault vault, UUID owner, OffsetDateTime now, String text) throws IOException {
		String content = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
		if (content.isEmpty()) {
			throw new IllegalArgumentException("收集内容不能为空");
		}
		String time = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
		String tagged = "- " + time + " (" + label(now.getOffset()) + ") " + content + "\n";
		String plain = "- " + time + " " + content + "\n";
		// 新文件的第一条一定标注时区；之后和最近一条带标注的记录同一个时区就不标，换了时区（或一条标注都没有）才标
		return appendLine(vault, owner, now, tagged, body -> now.getOffset().equals(lastOffset(body)) ? plain : tagged);
	}

	/**
	 * 记一条客户端已经加密好的随手记（InboxCipher.seal 的结果，一行 "MPALBOX1:…"）。时刻和时区标注在密文里，
	 * 服务端看不到内容，原样追加成一行。不是合法的密文行 → IllegalArgumentException。其余规则同 append。
	 */
	public static NoteSummary appendSealed(Vault vault, UUID owner, OffsetDateTime now, String sealedLine) throws IOException {
		if (!InboxCipher.isSealed(sealedLine)) {
			throw new IllegalArgumentException("不是收件箱密文");
		}
		String line = sealedLine.strip() + "\n";
		return appendLine(vault, owner, now, line, body -> line);
	}

	/**
	 * 一条要加密的随手记的明文："- HH:mm (UTC+8) 内容"（不带换行）。客户端加密前用它拼好这一行：
	 * 服务端看不到内容，没法按文件里上一条的时区决定标不标，所以加密的每一条都带时区标注。
	 * 换行换成空格、去掉首尾空白；处理后为空 → IllegalArgumentException。
	 */
	public static String entryLine(OffsetDateTime now, String text) {
		String content = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
		if (content.isEmpty()) {
			throw new IllegalArgumentException("收集内容不能为空");
		}
		return "- " + now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)) + " (" + label(now.getOffset()) + ") " + content;
	}

	/** 当天的收件箱最多分成这么多篇（一篇被加密了就记到下一篇）。 */
	public static final int MAX_PARTS = 10;

	private static NoteSummary appendLine(Vault vault, UUID owner, OffsetDateTime now, String firstLine,
			java.util.function.UnaryOperator<String> lineAfter) throws IOException {
		String date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
		synchronized (vault) {
			// 同步外部编辑的路径和加密标记
			vault.scan();
			for (int part = 1; part <= MAX_PARTS; part++) {
				String path = pathFor(owner, now, part);
				for (int attempt = 0; attempt < 2; attempt++) {
					NoteSummary note = vault.resolve(path).orElse(null);
					if (note == null) {
						if (attempt > 0) {
							throw new IOException("收件箱笔记已不存在");
						}
						String title = "# " + FOLDER + " " + date + (part == 1 ? "" : " (" + part + ")");
						return vault.createNote(path, title + "\n\n" + firstLine, owner);
					}
					if (!java.util.Objects.equals(note.access().owner(), owner)) {
						throw new NotYoursException();
					}
					String body = note.encrypted() ? null : vault.readBody(note.id())
							.orElseThrow(() -> new IOException("无法读取收件箱笔记"));
					if (body == null || NoteCipher.isEncrypted(body) || NoteEnvelope.isEnvelope(body)) {
						break; // 这一篇已经是加密笔记，记到下一篇
					}
					if (body.length() >= MAX_CHARS) {
						throw new FullException();
					}
					String updated = body + (body.endsWith("\n") ? "" : "\n") + lineAfter.apply(body);
					Vault.WriteResult result = vault.writeBody(note.id(), updated, Vault.hash(body));
					if (result == Vault.WriteResult.OK) {
						return vault.find(note.id()).orElseThrow(() -> new IOException("无法找到追加后的收件箱笔记"));
					}
					if (result == Vault.WriteResult.MISSING) {
						throw new IOException("收件箱笔记已不存在");
					}
					vault.scan(); // 写入冲突：重新读一次再试
				}
			}
			throw new IllegalStateException("今天的收件箱都已加密，不能再追加");
		}
	}

	/** owner 在 now 这一天的收件箱文件路径（owner 为 null = 本机房主）。 */
	public static String pathFor(UUID owner, OffsetDateTime now) {
		return pathFor(owner, now, 1);
	}

	/** 当天的第 part 篇：第 1 篇 "yyyy-MM-dd.md"，之后 "yyyy-MM-dd-2.md"…… */
	public static String pathFor(UUID owner, OffsetDateTime now, int part) {
		String date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
		return folderFor(owner) + "/" + date + (part == 1 ? "" : "-" + part) + ".md";
	}

	/** owner 的收件箱文件夹："收件箱" 或 "players/&lt;UUID&gt;/收件箱"。 */
	public static String folderFor(UUID owner) {
		return owner == null ? FOLDER : NoteAccess.personalFolder(owner) + "/" + FOLDER;
	}

	/** 这个文件夹是不是（某人的）收件箱，或者在收件箱里面："收件箱/…" 或 "players/&lt;任意&gt;/收件箱/…"。 */
	public static boolean isInboxFolder(String folder) {
		String rest = folder;
		String players = NoteAccess.PLAYERS_FOLDER + "/";
		if (rest.startsWith(players)) {
			int slash = rest.indexOf('/', players.length());
			if (slash < 0) {
				return false;
			}
			rest = rest.substring(slash + 1);
		}
		return rest.equals(FOLDER) || rest.startsWith(FOLDER + "/");
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
