package com.memorypalace.core.vault;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;
import com.memorypalace.core.crypto.Ciphertext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 历史版本：游戏内每保存一次笔记，就把保存前的正文留一份到 .palace/history/&lt;笔记ID&gt;/。
 *
 * 像 git 一样按行存差异：最新的一份存全文（&lt;UTC 时间&gt;.md），更早的存成"从后一份变回这一份"的差异
 * （&lt;UTC 时间&gt;.diff，统一差异格式，第一行 "--- &lt;后一份的ID&gt;" 写明基于哪一份）。
 * 每连着 MAX_CHAIN 份差异留一份全文，读最早的版本也不用一路倒推太久。加密笔记的版本是密文，没法比差异，都存全文。
 *
 * 留多少（每次保存都留，但防止被刷爆硬盘）：
 * - 最近 RECENT（30）份都留
 * - 更早的：HOURLY_FOR（7 天）内每小时留最后一份；DAILY_FOR（1 年）内每天留最后一份；再早的删掉
 * - 每篇的历史文件加起来不超过 MAX_BYTES（4 MB），超了从最早的删起
 * 保存本身另有每人限速（EditPayloads），一直刷保存也只能挤掉"最近 30 份"，更早的每小时、每天那几份挤不掉。
 *
 * - 只存正文（加密笔记就是密文，不解密）
 * - 明文笔记被加密后，清掉它以前的明文历史（差异文件也是明文）：不能在笔记加密后还躺在硬盘上（正文写好之后才清，
 *   之后每次保存加密笔记都再清一遍，上次没清干净的接着清，见 purgePlain）
 * - 在外部编辑器里改的不记（由 VaultBackup 的整库备份管）
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteHistory {
	/** 最近这么多份都留。 */
	public static final int RECENT = 30;
	/** 这段时间内每小时留一份。 */
	public static final Duration HOURLY_FOR = Duration.ofDays(7);
	/** 这段时间内每天留一份；再早的删掉。 */
	public static final Duration DAILY_FOR = Duration.ofDays(365);
	/** 每篇的历史文件加起来最多这么多字节。 */
	public static final long MAX_BYTES = 4L << 20;
	/** 最多连着这么多份差异，之后留一份全文。 */
	static final int MAX_CHAIN = 20;
	/** 正文超过这么多字符就不比差异、直接存全文（比差异太费时间）。 */
	static final int DIFF_LIMIT = 200_000;

	// 文件名用 UTC、精确到毫秒：按名字排序就是按时间排序
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", Locale.ROOT)
			.withZone(ZoneOffset.UTC);
	private static final Pattern VERSION_ID = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}\\.[0-9]{3}Z");
	private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
	private static final String FULL = ".md";
	private static final String DIFF = ".diff";

	/**
	 * 一份历史版本：id（文件名去掉扩展名）、保存的时刻、字数（char 数）、是不是密文、
	 * 比更早的一份多了几行、少了几行（最早的一份、密文、太长没比的为 -1）。
	 */
	public record Version(String id, Instant time, long chars, boolean encrypted, int added, int removed) {}

	/** 硬盘上的一份：全文或差异。 */
	private record Stored(String id, Instant time, Path file, boolean diff, long size) {}

	private final Path root;
	private final Clock clock;

	/** vaultRoot：笔记库根目录（历史放在它下面的 .palace/history/）。clock 的时区决定"每小时、每天"按哪里的钟点算。 */
	public NoteHistory(Path vaultRoot, Clock clock) {
		this.root = vaultRoot.resolve(".palace").resolve("history");
		this.clock = clock;
	}

	/** 版本 ID 格式对不对（命令里传进来的，防止路径穿越）。 */
	public static boolean validId(String id) {
		return id != null && VERSION_ID.matcher(id).matches();
	}

	/**
	 * 一篇笔记正文要从 oldBody 改成 newBody 了：存一份 oldBody（和最新一份一样就不重复存）。
	 * force = 还原（调用方要求一定存下来）：现在每次保存都存，所以和普通保存一样，只是失败时调用方不写入。
	 * 存不下来不抛异常（不能因为历史存不了就不让保存），返回 false。
	 */
	public synchronized boolean record(UUID noteId, String oldBody, String newBody, boolean force) {
		try {
			if (!Ciphertext.isEncrypted(oldBody) && Ciphertext.isEncrypted(newBody)) {
				// 明文 → 密文：不留这份明文。以前的明文历史在正文写好之后由 purgePlain 清掉
				// （先清再写的话，写失败时历史就白丢了）
				return true;
			}
			if (oldBody.equals(newBody)) {
				return true;
			}
			if (Ciphertext.isEncrypted(oldBody)) {
				// 已经是加密笔记：顺手清掉漏网的明文历史
				purgePlain(noteId);
			}
			List<Stored> stored = stored(noteId);
			Map<String, Stored> byId = index(stored);
			Map<String, String> cache = new HashMap<>();
			Stored newest = stored.isEmpty() ? null : stored.get(0);
			String newestText = newest == null ? null : text(newest.id(), byId, cache);
			if (oldBody.equals(newestText)) {
				return true; // 和最新一份一样，不重复存
			}
			Path dir = root.resolve(noteId.toString());
			Files.createDirectories(dir);
			// 同一毫秒已有（几乎不可能）就往后挪一毫秒，保证名字唯一、按名字排序仍是按时间
			Instant stamp = clock.instant();
			if (newest != null && !stamp.isAfter(newest.time())) {
				stamp = newest.time().plusMillis(1); // 时钟往回拨了：仍然排在最新
			}
			while (Files.exists(dir.resolve(NAME.format(stamp) + FULL)) || Files.exists(dir.resolve(NAME.format(stamp) + DIFF))) {
				stamp = stamp.plusMillis(1);
			}
			String id = NAME.format(stamp);
			writeAtomically(dir, id + FULL, oldBody);
			// 原来最新的那份改存成"从这份变回它"的差异
			if (newest != null && !newest.diff() && newestText != null && diffable(newestText) && diffable(oldBody)
					&& chainBelow(stored) < MAX_CHAIN) {
				try {
					writeAtomically(dir, newest.id() + DIFF, unifiedDiff(id, oldBody, newest.id(), newestText));
					Files.delete(newest.file());
				} catch (IOException e) {
					// 改不成差异也没关系：全文照样能读（同时有 .md 和 .diff 时读 .md）
				}
			}
			prune(noteId);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** 这篇的历史版本，最新的在前。没有就是空列表；读不出来的（差异对不上等）跳过。 */
	public synchronized List<Version> list(UUID noteId) throws IOException {
		List<Stored> stored = stored(noteId);
		Map<String, Stored> byId = index(stored);
		Map<String, String> cache = new HashMap<>();
		List<String> ids = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		for (Stored s : stored) {
			String text = text(s.id(), byId, cache);
			if (text != null) {
				ids.add(s.id());
				texts.add(text);
			}
		}
		List<Version> out = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) {
			String text = texts.get(i);
			int added = -1;
			int removed = -1;
			if (i + 1 < ids.size()) {
				String older = texts.get(i + 1);
				if (!Ciphertext.isEncrypted(text) && !Ciphertext.isEncrypted(older) && diffable(text) && diffable(older)) {
					added = 0;
					removed = 0;
					for (AbstractDelta<String> delta : DiffUtils.diff(lines(older), lines(text)).getDeltas()) {
						added += delta.getTarget().size();
						removed += delta.getSource().size();
					}
				}
			}
			out.add(new Version(ids.get(i), byId.get(ids.get(i)).time(), text.length(), Ciphertext.isEncrypted(text), added, removed));
		}
		return out;
	}

	/** 读一份历史版本的正文。ID 格式不对、不存在、读不出来 → empty。 */
	public synchronized Optional<String> read(UUID noteId, String versionId) throws IOException {
		if (!validId(versionId)) {
			return Optional.empty();
		}
		List<Stored> stored = stored(noteId);
		return Optional.ofNullable(text(versionId, index(stored), new HashMap<>()));
	}

	/**
	 * 删掉这篇的明文历史（笔记现在是加密的：不能还有明文副本躺在硬盘上）。差异文件都是明文，一律删。
	 * 每次加密笔记保存成功后都调用：上次没删干净（文件被占用等），下次接着删。全删掉了返回 true。
	 */
	public synchronized boolean purgePlain(UUID noteId) {
		Path dir = root.resolve(noteId.toString());
		boolean ok = true;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				try {
					// 只留认得出来的密文全文；明文、差异、认不出来的、写了一半的临时文件都删
					boolean keep = name.endsWith(FULL) && validId(name.substring(0, name.length() - FULL.length()))
							&& Ciphertext.isEncrypted(Files.readString(file, StandardCharsets.UTF_8));
					if (!keep) {
						Files.deleteIfExists(file);
					}
				} catch (IOException | RuntimeException e) {
					ok = false;
				}
			}
		} catch (NoSuchFileException e) {
			return true;
		} catch (IOException | RuntimeException e) {
			return false; // 包括遍历中途的 DirectoryIteratorException：尽力而为，下次再清
		}
		return ok;
	}

	/** 删掉这篇的全部历史。 */
	public synchronized void purge(UUID noteId) throws IOException {
		Path dir = root.resolve(noteId.toString());
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				Files.deleteIfExists(file);
			}
		} catch (NoSuchFileException e) {
			return;
		}
		Files.deleteIfExists(dir);
	}

	// ---- 存储 ----

	/** 硬盘上的版本，最新的在前。同一个 ID 既有全文又有差异（改存差异时中断）时用全文。 */
	private List<Stored> stored(UUID noteId) throws IOException {
		Path dir = root.resolve(noteId.toString());
		Map<String, Stored> found = new HashMap<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				boolean diff = name.endsWith(DIFF);
				if (!diff && !name.endsWith(FULL)) {
					continue;
				}
				String id = name.substring(0, name.length() - (diff ? DIFF : FULL).length());
				if (!validId(id) || !Files.isRegularFile(file)) {
					continue;
				}
				try {
					Stored s = new Stored(id, Instant.from(NAME.parse(id)), file, diff, Files.size(file));
					found.merge(id, s, (a, b) -> a.diff() ? b : a);
				} catch (DateTimeParseException | IOException e) {
					// 读不了的跳过
				}
			}
		} catch (NoSuchFileException e) {
			return List.of();
		}
		List<Stored> out = new ArrayList<>(found.values());
		out.sort(Comparator.comparing(Stored::id).reversed());
		return out;
	}

	private static Map<String, Stored> index(List<Stored> stored) {
		Map<String, Stored> byId = new HashMap<>();
		stored.forEach(s -> byId.put(s.id(), s));
		return byId;
	}

	/** 某一份的正文：全文直接读，差异从它基于的那份（更新的）倒推。读不出来为 null。 */
	private static String text(String id, Map<String, Stored> byId, Map<String, String> cache) {
		// 从这份沿着"基于哪份"往新的方向找到全文，再一路倒推回来；每一步的基准都必须更新，所以不会绕圈
		List<Stored> chain = new ArrayList<>();
		List<List<String>> diffs = new ArrayList<>();
		String base = null;
		String current = id;
		while (true) {
			String known = cache.get(current);
			if (known != null) {
				base = known;
				break;
			}
			Stored s = byId.get(current);
			if (s == null) {
				return null;
			}
			try {
				if (!s.diff()) {
					base = Files.readString(s.file(), StandardCharsets.UTF_8);
					cache.put(s.id(), base);
					break;
				}
				List<String> diff = fileLines(s.file());
				String next = baseOf(diff);
				if (next == null || next.compareTo(s.id()) <= 0) {
					return null;
				}
				chain.add(s);
				diffs.add(diff);
				current = next;
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}
		for (int i = chain.size() - 1; i >= 0; i--) {
			try {
				base = applyDiff(base, diffs.get(i));
			} catch (IOException e) {
				return null;
			}
			cache.put(chain.get(i).id(), base);
		}
		return base;
	}

	/** 最新那份往后（更早的方向）连着几份差异。 */
	private static int chainBelow(List<Stored> stored) {
		int n = 0;
		for (int i = 1; i < stored.size() && stored.get(i).diff(); i++) {
			n++;
		}
		return n;
	}

	private static boolean diffable(String text) {
		return text.length() <= DIFF_LIMIT && !Ciphertext.isEncrypted(text);
	}

	/**
	 * 按留存规则删掉多余的版本。被删的那份是别的差异的基准时，先把那份差异改存成全文，再删。
	 */
	private void prune(UUID noteId) throws IOException {
		List<Stored> stored = stored(noteId);
		Instant now = clock.instant();
		var zone = clock.getZone();
		Set<String> keep = new HashSet<>();
		Set<String> buckets = new HashSet<>();
		long bytes = 0;
		for (int i = 0; i < stored.size(); i++) {
			Stored s = stored.get(i);
			Duration age = Duration.between(s.time(), now);
			boolean wanted;
			if (i < RECENT) {
				wanted = true;
			} else if (age.compareTo(HOURLY_FOR) <= 0) {
				// 最新的在前：每个小时第一个遇到的就是这个小时最后一份
				wanted = buckets.add("h" + s.time().atZone(zone).truncatedTo(ChronoUnit.HOURS));
			} else if (age.compareTo(DAILY_FOR) <= 0) {
				wanted = buckets.add("d" + s.time().atZone(zone).toLocalDate());
			} else {
				wanted = false;
			}
			if (wanted && i > 0 && bytes + s.size() > MAX_BYTES) {
				break; // 超了总大小：这一份和更早的都不留
			}
			if (wanted) {
				keep.add(s.id());
				bytes += s.size();
			}
		}
		if (keep.size() == stored.size()) {
			return;
		}
		Map<String, Stored> byId = index(stored);
		Map<String, String> cache = new HashMap<>();
		Path dir = root.resolve(noteId.toString());
		// 留下的差异如果基于要删的那份：先改存成全文
		for (Stored s : stored) {
			if (!keep.contains(s.id()) || !s.diff()) {
				continue;
			}
			String base = baseOf(fileLines(s.file()));
			if (base != null && keep.contains(base)) {
				continue;
			}
			String text = text(s.id(), byId, cache);
			if (text == null) {
				keep.remove(s.id()); // 本来就读不出来了：一起删掉
				continue;
			}
			writeAtomically(dir, s.id() + FULL, text);
			Files.deleteIfExists(s.file());
		}
		for (Stored s : stored) {
			if (!keep.contains(s.id())) {
				Files.deleteIfExists(s.file());
			}
		}
	}

	private static void writeAtomically(Path dir, String name, String content) throws IOException {
		Path temporary = Files.createTempFile(dir, ".palace-", ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			Path target = dir.resolve(name);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); // 文件系统不支持原子移动
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	// ---- 差异格式 ----

	/** 按 "\n" 分行，保留末尾的空行：分开再用 "\n" 拼回去和原来一字不差。 */
	private static List<String> lines(String text) {
		return Arrays.asList(text.split("\n", -1));
	}

	private static List<String> fileLines(Path file) throws IOException {
		String content = Files.readString(file, StandardCharsets.UTF_8);
		List<String> out = new ArrayList<>(lines(content));
		if (!out.isEmpty() && out.getLast().isEmpty()) {
			out.removeLast(); // 文件末尾的换行
		}
		return out;
	}

	/** 差异文件第一行 "--- &lt;基准ID&gt;"。 */
	private static String baseOf(List<String> diff) {
		if (diff.isEmpty() || !diff.getFirst().startsWith("--- ")) {
			return null;
		}
		String base = diff.getFirst().substring(4).strip();
		return validId(base) ? base : null;
	}

	/** 统一差异格式（不带上下文行）：从 baseId 的正文 base 变成 targetId 的正文 target。 */
	static String unifiedDiff(String baseId, String base, String targetId, String target) {
		Patch<String> patch = DiffUtils.diff(lines(base), lines(target));
		StringBuilder out = new StringBuilder();
		out.append("--- ").append(baseId).append('\n');
		out.append("+++ ").append(targetId).append('\n');
		for (AbstractDelta<String> delta : patch.getDeltas()) {
			Chunk<String> from = delta.getSource();
			Chunk<String> to = delta.getTarget();
			// 统一差异的行号从 1 开始；某一边是 0 行时写它前面那一行的行号
			out.append("@@ -").append(from.size() == 0 ? from.getPosition() : from.getPosition() + 1).append(',').append(from.size())
					.append(" +").append(to.size() == 0 ? to.getPosition() : to.getPosition() + 1).append(',').append(to.size())
					.append(" @@\n");
			from.getLines().forEach(line -> out.append('-').append(line).append('\n'));
			to.getLines().forEach(line -> out.append('+').append(line).append('\n'));
		}
		return out.toString();
	}

	/** 把差异用到 base 上；对不上（文件被改坏了）→ IOException。 */
	static String applyDiff(String base, List<String> diff) throws IOException {
		record Hunk(int index, List<String> remove, List<String> add) {}
		List<Hunk> hunks = new ArrayList<>();
		Hunk hunk = null;
		for (int i = 2; i < diff.size(); i++) {
			String line = diff.get(i);
			Matcher m = HUNK.matcher(line);
			if (m.matches()) {
				int start = Integer.parseInt(m.group(1));
				int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
				hunk = new Hunk(count == 0 ? start : start - 1, new ArrayList<>(), new ArrayList<>());
				hunks.add(hunk);
			} else if (hunk != null && line.startsWith("-")) {
				hunk.remove().add(line.substring(1));
			} else if (hunk != null && line.startsWith("+")) {
				hunk.add().add(line.substring(1));
			} else {
				throw new IOException("历史差异格式不对");
			}
		}
		List<String> out = new ArrayList<>(lines(base));
		// 从后往前改，前面的行号不受影响
		for (int i = hunks.size() - 1; i >= 0; i--) {
			Hunk h = hunks.get(i);
			int end = h.index() + h.remove().size();
			if (h.index() < 0 || end > out.size() || !out.subList(h.index(), end).equals(h.remove())
					|| (i > 0 && hunks.get(i - 1).index() + hunks.get(i - 1).remove().size() > h.index())) {
				throw new IOException("历史差异对不上");
			}
			out.subList(h.index(), end).clear();
			out.addAll(h.index(), h.add());
		}
		return String.join("\n", out);
	}
}
