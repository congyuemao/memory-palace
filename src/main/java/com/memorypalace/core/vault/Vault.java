package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.NoteCipher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 笔记库：一个装着 .md 文件的普通文件夹（默认 saves/&lt;世界&gt;/memory-palace/）。
 * 世界里的笔记书只存 UUID，靠这里把 UUID 翻译回文件。
 * 线程安全：所有公开方法可以从游戏的服务端线程、后台线程和客户端线程同时调用。
 * 索引的读（find、list、resolve、listFolder）不拿锁；读写文件、改索引都在 this 锁里。
 * 读过的正文按文件的修改时间和大小缓存：没变就不再读盘（搜索、反向链接会把每篇都读一遍）。
 */
public final class Vault {
	private final Path root;
	// ID → 目录卡片；路径（不带 .md）→ ID。只在 this 锁里改，读不用锁
	private final Map<UUID, NoteSummary> index = new ConcurrentHashMap<>();
	private final Map<String, UUID> byPath = new ConcurrentHashMap<>();
	// 按路径排好的全部卡片：改了索引就作废，下次 list() 时重排
	private volatile List<NoteSummary> sorted = List.of();
	private volatile boolean sortedStale;
	// 读过的正文（见 readNote）。按总字数限制大小，最久没用的先丢
	private static final long CACHE_CHARS = 8_000_000;
	private final java.util.LinkedHashMap<UUID, Cached> cache = new java.util.LinkedHashMap<>(256, 0.75F, true);
	private long cachedChars;

	/** 缓存的一篇：读的时候文件的路径、修改时间、大小，和当时做出来的卡片、正文。 */
	private record Cached(String relativePath, long mtime, long size, NoteSummary note, String body) {}

	/** root 不存在时自动创建。 */
	public Vault(Path root) throws IOException {
		this.root = root.toAbsolutePath().normalize();
		Files.createDirectories(this.root);
	}

	public Path root() {
		return root;
	}

	/**
	 * 重新扫描整个笔记库，重建 UUID → 文件 的索引。
	 * - 递归查找所有 .md 文件（扩展名不区分大小写）
	 * - 跳过名字以 "." 开头的文件和文件夹（例如 .palace/、.obsidian/）
	 * - 没有 palace-id（或 palace-id 不合法）的笔记：生成新 UUID 并写回文件（用 FrontMatter.withId）
	 * - 两个文件 palace-id 相同（比如复制了文件）：按 relativePath 字典序，第一个保留，
	 *   其余的生成新 UUID 并写回
	 * - 写文件必须"原子"：先写同目录临时文件，再 move 替换（ATOMIC_MOVE，失败则退回 REPLACE_EXISTING）
	 * - 读写一律 UTF-8
	 * - 单个文件读失败不能让整个扫描失败：跳过它并继续
	 */
	public synchronized void scan() throws IOException {
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return !dir.equals(root) && dir.getFileName().toString().startsWith(".")
						? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (attrs.isRegularFile() && !name.startsWith(".") && isMarkdown(name)) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
				if (file.equals(root)) {
					throw error;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		files.sort(Comparator.comparing(this::relativePath));
		Map<UUID, NoteSummary> newIndex = new HashMap<>();
		Map<UUID, Long> newModified = new HashMap<>();
		List<Cached> fresh = new ArrayList<>();
		Map<String, Cached> cachedByPath = new HashMap<>();
		for (Cached c : cache.values()) {
			cachedByPath.put(c.relativePath(), c);
		}
		// 先并行查属性、读内容（读盘、杀毒软件扫描的等待可以重叠），再按路径顺序处理（分配 ID 的结果要稳定）
		long[][] stats = new long[files.size()][];
		String[] texts = new String[files.size()];
		java.util.stream.IntStream.range(0, files.size()).parallel().forEach(i -> {
			Path file = files.get(i);
			stats[i] = stat(file);
			Cached known = cachedByPath.get(relativePath(file));
			if (known != null && stats[i] != null && known.mtime() == stats[i][0] && known.size() == stats[i][1]) {
				return; // 没变过，下面直接用缓存
			}
			try {
				texts[i] = Files.readString(file, StandardCharsets.UTF_8);
			} catch (IOException e) {
				// 读不了：下面跳过这个文件
			}
		});
		for (int i = 0; i < files.size(); i++) {
			Path file = files.get(i);
			String rel = relativePath(file);
			long[] stat = stats[i];
			// 没变过的文件（路径、修改时间、大小都和缓存一样）不用再读
			Cached known = cachedByPath.get(rel);
			if (known != null && stat != null && known.mtime() == stat[0] && known.size() == stat[1]
					&& !newIndex.containsKey(known.note().id())) {
				newIndex.put(known.note().id(), known.note());
				newModified.put(known.note().id(), stat[0]);
				continue;
			}
			String text = texts[i];
			long mtime = stat == null ? -1 : stat[0];
			if (text == null) {
				continue;
			}
			FrontMatter.Parsed parsed = FrontMatter.parse(text);
			UUID id = parsed.id();
			boolean encrypted = parsed.fields().containsKey("palace-encrypted") || Ciphertext.isEncrypted(parsed.body());
			if (encrypted && (id == null || newIndex.containsKey(id))) {
				// 加密笔记的密文绑定了原来的 ID：绝不能改它的 ID（改了就再也解不开）。
				// 重复或缺 ID 的加密文件先不收录，原样保留在硬盘上
				continue;
			}
			if (id == null || newIndex.containsKey(id)) {
				do {
					id = UUID.randomUUID();
				} while (newIndex.containsKey(id));
				try {
					writeAtomically(file, FrontMatter.withId(text, id));
				} catch (IOException e) {
					// 写不回 ID（只读文件等）就先不收录：只存在内存里的 ID 重启后会变，笔记书会失效
					continue;
				}
			}
			NoteSummary note = summarize(id, rel, parsed);
			newIndex.put(id, note);
			newModified.put(id, mtime);
			if (stat != null && id.equals(parsed.id())) {
				// 读过的顺便记进缓存：下次扫描、搜索不用再读（写回了新 ID 的文件内容变了，不记）
				fresh.add(new Cached(rel, stat[0], stat[1], note, parsed.body()));
			}
		}
		index.clear();
		byPath.clear();
		modified.clear();
		index.putAll(newIndex);
		modified.putAll(newModified);
		for (NoteSummary note : newIndex.values()) {
			byPath.put(stemOf(note.relativePath()), note.id());
		}
		cache.values().removeIf(c -> !c.note().equals(index.get(c.note().id())));
		cachedChars = cache.values().stream().mapToLong(c -> c.body().length()).sum();
		for (Cached c : fresh) {
			if (c.note().equals(index.get(c.note().id()))) {
				remember(c);
			}
		}
		sortedStale = true;
	}

	/** 从解析好的文件内容做一张目录卡片（scan 和 readNote 共用，保证两边算法一样）。 */
	private static NoteSummary summarize(UUID id, String relativePath, FrontMatter.Parsed parsed) {
		boolean encrypted = parsed.fields().containsKey("palace-encrypted") || Ciphertext.isEncrypted(parsed.body());
		String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
		// 标记存在即隐藏正文标题，即使文件内容不一致也不能泄露标题。
		String title = encrypted ? name.substring(0, name.length() - 3) : parsed.body().lines()
				.filter(line -> line.startsWith("# "))
				.map(line -> line.substring(2).strip())
				.findFirst().orElse(name.substring(0, name.length() - 3));
		return new NoteSummary(id, relativePath, title, encrypted, NoteAccess.parse(parsed.fields()));
	}

	/** 只更新索引里的一篇（文件刚读过，权限、标题、加密状态以文件为准）。mtime 是读的那一刻文件的修改时间。调用方持有锁。 */
	private void updateIndex(NoteSummary fresh, long mtime) {
		modified.put(fresh.id(), mtime);
		NoteSummary old = index.put(fresh.id(), fresh);
		if (fresh.equals(old)) {
			return;
		}
		if (old != null && !old.relativePath().equals(fresh.relativePath())) {
			byPath.remove(stemOf(old.relativePath()), fresh.id());
		}
		byPath.put(stemOf(fresh.relativePath()), fresh.id());
		sortedStale = true;
	}

	/** 从索引里去掉一篇（文件已经不在了）。调用方持有锁。 */
	private void removeFromIndex(UUID id) {
		NoteSummary old = index.remove(id);
		modified.remove(id);
		uncache(id);
		if (old != null) {
			byPath.remove(stemOf(old.relativePath()), id);
			sortedStale = true;
		}
	}

	private void remember(Cached entry) {
		uncache(entry.note().id());
		if (entry.body().length() > CACHE_CHARS / 4) {
			return; // 特别大的不缓存，免得一篇就挤掉全部
		}
		cache.put(entry.note().id(), entry);
		cachedChars += entry.body().length();
		var it = cache.values().iterator();
		while (cachedChars > CACHE_CHARS && it.hasNext()) {
			cachedChars -= it.next().body().length();
			it.remove();
		}
	}

	private void uncache(UUID id) {
		Cached old = cache.remove(id);
		if (old != null) {
			cachedChars -= old.body().length();
		}
	}

	/** 路径去掉 .md（按路径找笔记时用的键）。 */
	private static String stemOf(String relativePath) {
		return isMarkdown(relativePath) ? relativePath.substring(0, relativePath.length() - 3) : relativePath;
	}

	/** 文件的修改时间和大小；读不到（不存在等）时为 null。 */
	private static long[] stat(Path file) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			return new long[]{attrs.lastModifiedTime().toMillis(), attrs.size()};
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * 找不到文件时要不要重新扫描：只为"认识的 ID"扫（文件在外部改了名，靠扫描按 ID 找回来；
	 * 找不回来的会从索引里去掉，之后就不认识了，不会反复扫）。
	 * 不认识的 ID 一律不扫：ID 是客户端随便发的，不能让随机 ID 把全库读盘刷成拒绝服务。
	 * 在外部新放进来的笔记要等管理员 /palace rescan（或重进世界）才认识。
	 */
	private boolean mayRescanFor(UUID id) {
		return index.containsKey(id);
	}

	// 每篇笔记文件上次读到时的修改时间：refreshChanged 用它发现"在外部被改过"的文件
	private final Map<UUID, Long> modified = new ConcurrentHashMap<>();

	private static long modifiedTime(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			return -1;
		}
	}

	/**
	 * 重新读一遍"修改时间变了"的笔记，更新它们的目录卡片（权限、标题、加密状态）。
	 * 只查文件属性，没变的不读内容，可以定时调用（服务端每隔几秒在后台调一次），
	 * 这样在外部改了 palace-read / palace-edit 之后，搜索、准星书名、列表等也很快按新权限来。
	 * 有文件不见了（被删、被改名）就整库重扫一次：按 ID 找回改了名的，删掉的从索引里去掉，
	 * 不让旧卡片（标题、路径、权限）一直留着。
	 */
	public void refreshChanged() throws IOException {
		// 查修改时间不拿锁（几千个文件也要一会儿），这期间保存、打开照常进行；只有真变了的才拿锁重读
		java.util.concurrent.atomic.AtomicBoolean missing = new java.util.concurrent.atomic.AtomicBoolean();
		List<UUID> changed = list().parallelStream().filter(note -> {
			long now = modifiedTime(root.resolve(note.relativePath()));
			if (now < 0) {
				missing.set(true);
				return false;
			}
			Long seen = modified.get(note.id());
			return seen == null || seen != now;
		}).map(NoteSummary::id).toList();
		for (UUID id : changed) {
			readNote(id); // 读不了就先保留旧卡片，下次再试
		}
		if (missing.get()) {
			scan();
		}
	}

	/** 一篇笔记的最新目录卡片和正文：同一次读文件得到，权限以这一刻的文件为准。 */
	public record Read(NoteSummary note, String body) {}

	/**
	 * 读一篇笔记，同时刷新它在索引里的目录卡片（权限、标题、加密状态可能在外部被改了）。
	 * 调用方应该用返回的 note 做权限检查，而不是之前 find() 拿到的旧卡片。
	 * 文件在扫描后被删掉了 → empty；改了名但 ID 没变 → 自动重新扫描一次再找。
	 */
	public synchronized Optional<Read> readNote(UUID id) throws IOException {
		if (id == null) {
			return Optional.empty();
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			NoteSummary note = index.get(id);
			if (note != null) {
				try {
					Path file = root.resolve(note.relativePath());
					long[] stat = stat(file);
					Cached known = cache.get(id);
					if (stat != null && known != null && known.relativePath().equals(note.relativePath())
							&& known.mtime() == stat[0] && known.size() == stat[1]) {
						modified.put(id, stat[0]);
						return Optional.of(new Read(known.note(), known.body()));
					}
					FrontMatter.Parsed parsed = FrontMatter.parse(Files.readString(file, StandardCharsets.UTF_8));
					if (id.equals(parsed.id())) {
						NoteSummary fresh = summarize(id, note.relativePath(), parsed);
						long mtime = stat == null ? -1 : stat[0];
						updateIndex(fresh, mtime);
						if (stat != null) {
							remember(new Cached(note.relativePath(), stat[0], stat[1], fresh, parsed.body()));
						}
						return Optional.of(new Read(fresh, parsed.body()));
					}
				} catch (NoSuchFileException e) {
					// 旧路径消失后重新扫描一次，以 ID 追踪改名后的文件。
				}
			}
			if (attempt == 0 && mayRescanFor(id)) {
				scan();
			} else {
				break;
			}
		}
		return Optional.empty();
	}

	/** 所有笔记，按 relativePath 字典序排序（不可修改的快照）。 */
	public List<NoteSummary> list() {
		if (sortedStale) {
			synchronized (this) {
				if (sortedStale) {
					sortedStale = false;
					List<NoteSummary> all = new ArrayList<>(index.values());
					all.sort(Comparator.comparing(NoteSummary::relativePath));
					sorted = List.copyOf(all);
				}
			}
		}
		return sorted;
	}

	/**
	 * 某个文件夹里"直接"的笔记（不含子文件夹里的），按 relativePath 排序。
	 * folder 用 "/" 分隔，"" 表示根目录；前后的 "/" 忽略。
	 */
	public List<NoteSummary> listFolder(String folder) {
		int start = 0;
		int end = folder.length();
		while (start < end && folder.charAt(start) == '/') {
			start++;
		}
		while (end > start && folder.charAt(end - 1) == '/') {
			end--;
		}
		String normalized = folder.substring(start, end);
		return list().stream().filter(note -> note.folder().equals(normalized)).toList();
	}

	public Optional<NoteSummary> find(UUID id) {
		return id == null ? Optional.empty() : Optional.ofNullable(index.get(id));
	}

	/**
	 * 按相对路径找笔记，例如 "线性代数/特征值.md"；可以省略 ".md"。分隔符 "/" 和 "\" 都接受。
	 * 路径如果跑到笔记库外面（含 ".."、绝对路径等）一律返回 empty，绝不抛异常。
	 */
	public Optional<NoteSummary> resolve(String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return Optional.empty();
		}
		String input = relativePath.replace('\\', '/');
		if (input.startsWith("/") || input.contains(":") || input.endsWith("/")) {
			return Optional.empty();
		}
		for (String part : input.split("/")) {
			if (part.equals("..")) {
				return Optional.empty();
			}
		}
		try {
			Path path = root.getFileSystem().getPath(input);
			if (path.isAbsolute() || path.getRoot() != null) {
				return Optional.empty();
			}
			Path target = root.resolve(path).normalize();
			if (!target.startsWith(root) || target.equals(root)) {
				return Optional.empty();
			}
			UUID id = byPath.get(stemOf(relativePath(target)));
			return id == null ? Optional.empty() : Optional.ofNullable(index.get(id));
		} catch (InvalidPathException | SecurityException e) {
			return Optional.empty();
		}
	}

	/**
	 * 读取笔记正文（去掉 front matter 之后的部分）。
	 * 文件在扫描后被删掉了 → 返回 empty。
	 * 文件在扫描后改了名但 ID 没变 → 自动重新 scan() 一次再找。
	 */
	public synchronized Optional<String> readBody(UUID id) throws IOException {
		return readNote(id).map(Read::body);
	}

	/** 笔记文件的绝对路径（给"用外部编辑器打开"按钮用）。 */
	public Optional<Path> pathOf(UUID id) {
		return find(id).map(note -> root.resolve(note.relativePath()));
	}

	/** writeBody 的结果。 */
	public enum WriteResult {
		/** 写好了。 */
		OK,
		/** 文件在打开编辑器之后被别人（外部编辑器）改过，没有写入。 */
		CONFLICT,
		/** 找不到这篇笔记（被删了）。 */
		MISSING,
		/** 按文件里此刻的权限，这个人不能改（见带 allowed 参数的 writeBody）。 */
		DENIED,
		/** 还原历史版本时，还原前的内容存不进历史，没有写入（见 NoteHistory）。 */
		HISTORY_FAILED
	}

	/**
	 * 正文的指纹：UTF-8 字节的 SHA-256，小写十六进制。编辑器打开时记下旧正文的指纹，保存时交给 writeBody 检查冲突。
	 * 比较前把 "\r\n" 统一成 "\n"（Windows 换行不算修改）。
	 */
	public static String hash(String body) {
		try {
			byte[] bytes = body.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("运行环境不支持 SHA-256", e);
		}
	}

	/**
	 * 用 newBody 替换笔记正文（front matter 部分一个字节都不动，包括 palace-id 和其他字段）。要求：
	 * - 例外（M6 加密）：newBody 是密文（NoteCipher.isEncrypted）时，确保 front matter 里有一行 "palace-encrypted: v1"；
	 *   newBody 不是密文时，去掉 front matter 里的 palace-encrypted 行。其他行仍然一个字节都不动
	 * - expectedHash 不为 null 时：先读出当前正文算 hash，和 expectedHash 不一样 → 返回 CONFLICT，不写
	 * - expectedHash 为 null：强制覆盖（用户在冲突提示里选了"覆盖"）
	 * - 原文件用 "\r\n" 换行的，新正文里的 "\n" 也换成 "\r\n"；原文件用 "\n" 的保持 "\n"
	 * - 原子写（临时文件 + move），UTF-8，不写 BOM
	 * - 写完后更新索引（标题可能变了），list()/find() 立刻能看到新标题
	 * - 找不到笔记 / 文件已被删 → MISSING（会先像 readBody 那样重新扫描一次再判断）
	 */
	public synchronized WriteResult writeBody(UUID id, String newBody, String expectedHash) throws IOException {
		return writeBody(id, newBody, expectedHash, note -> true);
	}

	/**
	 * 同上，但写之前先用"刚读出来的文件"做一张目录卡片交给 allowed 判断：不放行 → DENIED，不写。
	 * 读文件、判断权限、写文件在同一把锁里，权限以写入这一刻的文件为准（外部刚改过 palace-edit 也算数）。
	 */
	public synchronized WriteResult writeBody(UUID id, String newBody, String expectedHash,
			java.util.function.Predicate<NoteSummary> allowed) throws IOException {
		return writeBody(id, newBody, expectedHash, allowed, false);
	}

	// 历史版本（见 NoteHistory）：设置了就在 writeBody 真写文件之前存一份旧正文
	private NoteHistory history;

	public synchronized void setHistory(NoteHistory history) {
		this.history = history;
	}

	/** 历史版本；没设置时为 null。 */
	public synchronized NoteHistory history() {
		return history;
	}

	/**
	 * 同上；设置了历史版本（setHistory）时，真写文件之前按 NoteHistory 的规则存一份旧正文。
	 * restore = 这是一次还原：旧正文一定存一份（不受 10 分钟合并限制）。
	 */
	public synchronized WriteResult writeBody(UUID id, String newBody, String expectedHash,
			java.util.function.Predicate<NoteSummary> allowed, boolean restore) throws IOException {
		if (id == null) {
			return WriteResult.MISSING;
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			NoteSummary note = index.get(id);
			if (note != null) {
				Path file = root.resolve(note.relativePath());
				String text = null;
				try {
					text = Files.readString(file, StandardCharsets.UTF_8);
				} catch (NoSuchFileException e) {
					// 旧路径消失后重新扫描一次，以 ID 追踪改名后的文件。
				}
				if (text != null) {
					FrontMatter.Parsed parsed = FrontMatter.parse(text);
					if (id.equals(parsed.id())) {
						NoteSummary fresh = summarize(id, note.relativePath(), parsed);
						updateIndex(fresh, modifiedTime(file));
						if (!allowed.test(fresh)) {
							return WriteResult.DENIED;
						}
						if (expectedHash != null && !MessageDigest.isEqual(hash(parsed.body()).getBytes(StandardCharsets.UTF_8),
								expectedHash.getBytes(StandardCharsets.UTF_8))) {
							return WriteResult.CONFLICT;
						}
						String newline = text.contains("\r\n") ? "\r\n" : "\n";
						String body = newBody.replace("\r\n", "\n").replace("\n", newline);
						// 正文是原文的后缀，直接截取可保留字段格式、注释和空白。
						String frontMatter = text.substring(text.startsWith("\uFEFF") ? 1 : 0,
								text.length() - parsed.body().length());
						frontMatter = withEncryptionFlag(frontMatter, Ciphertext.isEncrypted(body), newline);
						if (!body.isEmpty() && !frontMatter.endsWith("\n")) {
							frontMatter += newline;
						}
						String written = frontMatter + body;
						// 内容一个字都没变：不写文件（也就不触发外部编辑器、热重载、备份那一串）
						if (!written.equals(text.startsWith("﻿") ? text.substring(1) : text)) {
							// 存不下历史：普通保存照样写（不能因为历史存不了就不让保存）；
							// 还原不写——还原前的内容没留下来，一还原就再也找不回来了
							if (history != null && !history.record(id, parsed.body().replace("\r\n", "\n"),
									body.replace("\r\n", "\n"), restore) && restore) {
								return WriteResult.HISTORY_FAILED;
							}
							writeAtomically(file, written);
						}
						// 现在是加密笔记：正文写好之后清掉明文历史（刚加密的、上次没清干净的）
						// 正文已经写好了：清理出任何问题都不能改变"保存成功"的结果（purgePlain 自己不抛异常，这里再兜一层）
						if (history != null && Ciphertext.isEncrypted(body)) {
							try {
								history.purgePlain(id);
							} catch (RuntimeException e) {
								// 下次保存时再清
							}
						}
						// 只更新这一篇的卡片，不整库重扫：保存可能很频繁，整库扫描会被刷成拒绝服务
						FrontMatter.Parsed saved = FrontMatter.parse(written);
						NoteSummary fresh2 = summarize(id, note.relativePath(), saved);
						long[] stat = stat(file);
						updateIndex(fresh2, stat == null ? -1 : stat[0]);
						if (stat != null) {
							remember(new Cached(note.relativePath(), stat[0], stat[1], fresh2, saved.body()));
						}
						return WriteResult.OK;
					}
				}
			}
			if (attempt == 0 && mayRescanFor(id)) {
				scan();
			} else {
				break;
			}
		}
		return WriteResult.MISSING;
	}

	/**
	 * 新建一篇笔记。要求：
	 * - relativePath 用 "/" 或 "\" 分隔，可以省略 ".md"（会自动加上）；中间的文件夹不存在就创建
	 * - 路径规则和 resolve 一样：不能跑出笔记库、不能是绝对路径、不能含 ".."；
	 *   文件名或任一级文件夹名不能为空、不能以 "." 开头（那是隐藏文件夹，不会被扫描）、不能含 Windows 不允许的字符 &lt;&gt;:"|?*
	 *   不合法 → IllegalArgumentException（message 用中文说明原因）
	 * - 文件已存在 → IllegalArgumentException（"笔记已存在"），不覆盖
	 * - 文件内容 = front matter（只有新生成的 palace-id）+ body；body 为空时用 "# 文件名\n\n"（文件名不含 .md）
	 * - 原子写，写完更新索引，返回新笔记的 NoteSummary
	 */
	/** 命令里输入的相对路径：不能为空、不能是绝对路径，每一段不能为空、不能以点开头、不能有 Windows 不允许的字符。 */
	private static String checkedRelative(String relativePath) {
		if (relativePath == null || relativePath.isBlank()) {
			throw new IllegalArgumentException("路径不能为空");
		}
		String input = relativePath.strip().replace('\\', '/');
		if (input.startsWith("/")) {
			throw new IllegalArgumentException("路径不能是绝对路径");
		}
		for (String part : input.split("/", -1)) {
			if (part.isEmpty()) {
				throw new IllegalArgumentException("文件名或文件夹名不能为空");
			}
			if (part.startsWith(".")) {
				throw new IllegalArgumentException("文件名或文件夹名不能以点开头，也不能使用 ..");
			}
			if (part.chars().anyMatch(c -> c < 32 || "<>:\"|?*".indexOf(c) >= 0)) {
				throw new IllegalArgumentException("文件名或文件夹名含有 Windows 不允许的字符");
			}
		}
		return input;
	}

	/** 笔记库里的这个相对路径（已经过 checkedRelative）；超出笔记库（含通过链接）→ IllegalArgumentException。 */
	private Path resolveInside(String input) throws IOException {
		Path file;
		try {
			Path path = root.getFileSystem().getPath(input);
			if (path.isAbsolute() || path.getRoot() != null) {
				throw new IllegalArgumentException("路径不能是绝对路径");
			}
			file = root.resolve(path).normalize();
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("路径不合法", e);
		}
		if (!file.startsWith(root) || file.equals(root)) {
			throw new IllegalArgumentException("路径不能超出笔记库");
		}
		Path ancestor = file.getParent();
		while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
			ancestor = ancestor.getParent();
		}
		if (!ancestor.toRealPath().startsWith(root.toRealPath())) {
			throw new IllegalArgumentException("路径不能通过链接超出笔记库");
		}
		return file;
	}

	/**
	 * 新建一个空文件夹（可以多层，已有的上层不动），返回它相对笔记库的路径。名字的规则和新建笔记一样；
	 * 已经存在 → IllegalArgumentException。
	 */
	public synchronized String createFolder(String relativePath) throws IOException {
		String input = relativePath == null ? null : relativePath.strip().replaceAll("[/\\\\]+$", "");
		Path dir = resolveInside(checkedRelative(input));
		if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("已经有同名的文件夹或文件");
		}
		Files.createDirectories(dir);
		return root.relativize(dir).toString().replace('\\', '/');
	}

	public synchronized NoteSummary createNote(String relativePath, String body) throws IOException {
		return createNote(relativePath, body, null);
	}

	/** 同上；owner 不为 null 时在 front matter 里写上 palace-owner（作者）。 */
	public synchronized NoteSummary createNote(String relativePath, String body, UUID owner) throws IOException {
		String input = checkedRelative(relativePath);
		if (!isMarkdown(input)) {
			input += ".md";
		}
		Path file = resolveInside(input);
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("笔记已存在");
		}
		String name = file.getFileName().toString();
		String newBody = body.isEmpty() ? "# " + name.substring(0, name.length() - 3) + "\n\n" : body;
		UUID id;
		do {
			id = UUID.randomUUID();
		} while (index.containsKey(id));
		String text = "---\npalace-id: " + id + "\n"
				+ (owner == null ? "" : NoteAccess.OWNER_KEY + ": " + owner + "\n") + "---\n" + newBody;
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
		try {
			Files.writeString(temporary, text, StandardCharsets.UTF_8);
			// ATOMIC_MOVE 可能覆盖已存在的目标，新建时必须保留存在性检查。
			Files.move(temporary, file);
		} catch (FileAlreadyExistsException e) {
			throw new IllegalArgumentException("笔记已存在", e);
		} finally {
			Files.deleteIfExists(temporary);
		}
		// 只把这一篇加进索引（整库重扫在笔记多的时候要好几秒）
		NoteSummary created = summarize(id, relativePath(file), FrontMatter.parse(text));
		updateIndex(created, modifiedTime(file));
		return created;
	}

	/**
	 * 给笔记改名（只改文件名，留在原来的文件夹里；不带 .md 时自动补上）。笔记 ID 不变，
	 * 所以书架上的书、历史版本、加密都不受影响。新名字不合法、和别的文件重名 → IllegalArgumentException。
	 */
	public synchronized NoteSummary renameNote(UUID id, String newName) throws IOException {
		NoteSummary note = find(id).orElseThrow(() -> new IllegalArgumentException("笔记不存在"));
		String name = checkedRelative(newName);
		if (name.contains("/")) {
			throw new IllegalArgumentException("新名字里不能有斜杠（改名不移动文件夹）");
		}
		if (!isMarkdown(name)) {
			name += ".md";
		}
		Path source = root.resolve(note.relativePath());
		Path target = source.resolveSibling(name);
		if (target.getFileName().toString().equals(source.getFileName().toString())) {
			return note;
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isSameFile(source, target)) {
				throw new IllegalArgumentException("这个文件夹里已经有同名的笔记");
			}
			// 只改大小写（Windows 上是同一个文件）：先挪到临时名字再改回来
			Path temporary = source.resolveSibling(".palace-rename-" + UUID.randomUUID() + ".tmp");
			Files.move(source, temporary);
			source = temporary;
		}
		Files.move(source, target);
		// 只改这一篇的卡片：加密笔记的标题就是文件名，要按新文件重新做一张
		uncache(id);
		FrontMatter.Parsed parsed = FrontMatter.parse(Files.readString(target, StandardCharsets.UTF_8));
		NoteSummary renamed = summarize(id, relativePath(target), parsed);
		updateIndex(renamed, modifiedTime(target));
		return renamed;
	}

	/**
	 * 把一篇笔记或一个文件夹（连同里面的东西）移进回收站 .palace/trash/&lt;UTC 时间&gt;/&lt;原来的路径&gt;，
	 * 不真的删掉：想找回就从那里挪回来。返回它在回收站里的路径（相对笔记库）。
	 * 路径不合法、不存在、是笔记库本身 → IllegalArgumentException。
	 */
	public synchronized String moveToTrash(String relativePath, java.time.Instant now) throws IOException {
		String input = checkedRelative(relativePath == null ? null : relativePath.strip().replaceAll("[/\\\\]+$", ""));
		Path source = resolveInside(input);
		if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("找不到这篇笔记或这个文件夹");
		}
		String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", java.util.Locale.ROOT)
				.withZone(java.time.ZoneOffset.UTC).format(now);
		Path bin = root.resolve(".palace").resolve("trash").resolve(stamp);
		Path target = bin.resolve(root.relativize(source));
		for (int n = 2; Files.exists(bin); n++) {
			bin = root.resolve(".palace").resolve("trash").resolve(stamp + "_" + n);
			target = bin.resolve(root.relativize(source));
		}
		Files.createDirectories(target.getParent());
		Files.move(source, target);
		// 只去掉挪走的那些（一篇，或者文件夹里的全部）
		String moved = relativePath(source);
		for (NoteSummary note : List.copyOf(index.values())) {
			if (note.relativePath().equals(moved) || note.relativePath().startsWith(moved + "/")) {
				removeFromIndex(note.id());
			}
		}
		return root.relativize(target).toString().replace('\\', '/');
	}

	/**
	 * 改一篇笔记的分享名单（front matter 里的 palace-read / palace-edit），正文和其他字段不动。
	 * 和 writeBody 一样：读文件、用刚读出来的卡片交给 allowed 判断、写文件在同一把锁里，权限以这一刻的文件为准。
	 * change 拿到现在的设置，返回新的设置；没变就不写文件。
	 * @return OK / MISSING / DENIED（不会是 CONFLICT）
	 */
	public synchronized WriteResult updateAccess(UUID id, java.util.function.UnaryOperator<NoteAccess.Rules> change,
			java.util.function.Predicate<NoteSummary> allowed) throws IOException {
		if (id == null) {
			return WriteResult.MISSING;
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			NoteSummary note = index.get(id);
			if (note != null) {
				Path file = root.resolve(note.relativePath());
				String text = null;
				try {
					text = Files.readString(file, StandardCharsets.UTF_8);
				} catch (NoSuchFileException e) {
					// 旧路径消失后重新扫描一次，以 ID 追踪改名后的文件。
				}
				if (text != null) {
					FrontMatter.Parsed parsed = FrontMatter.parse(text);
					if (id.equals(parsed.id())) {
						NoteSummary fresh = summarize(id, note.relativePath(), parsed);
						updateIndex(fresh, modifiedTime(file));
						if (!allowed.test(fresh)) {
							return WriteResult.DENIED;
						}
						NoteAccess.Rules changed = change.apply(fresh.access());
						if (changed.equals(fresh.access())) {
							return WriteResult.OK;
						}
						String written = text.startsWith("﻿") ? text.substring(1) : text;
						for (Map.Entry<String, String> field : NoteAccess.fields(changed).entrySet()) {
							written = FrontMatter.withField(written, field.getKey(), field.getValue());
						}
						writeAtomically(file, written);
						uncache(id);
						updateIndex(summarize(id, note.relativePath(), FrontMatter.parse(written)), modifiedTime(file));
						return WriteResult.OK;
					}
				}
			}
			if (attempt == 0 && mayRescanFor(id)) {
				scan();
			} else {
				break;
			}
		}
		return WriteResult.MISSING;
	}

	/** 输入是已解析、去 BOM 的完整 front matter；只编辑加密标记行。 */
	private static String withEncryptionFlag(String frontMatter, boolean encrypted, String newline) {
		int start = frontMatter.indexOf('\n') + 1;
		StringBuilder result = new StringBuilder(frontMatter.substring(0, start));
		boolean inserted = false;
		while (start < frontMatter.length()) {
			int lf = frontMatter.indexOf('\n', start);
			int next = lf < 0 ? frontMatter.length() : lf + 1;
			int end = lf < 0 ? frontMatter.length() : lf;
			if (lf >= 0 && end > start && frontMatter.charAt(end - 1) == '\r') {
				end--;
			}
			String line = frontMatter.substring(start, end);
			if (line.stripTrailing().equals("---")) {
				if (encrypted && !inserted) {
					result.append("palace-encrypted: v1").append(newline);
				}
				return result.append(frontMatter, start, frontMatter.length()).toString();
			}
			int colon = line.indexOf(':');
			if (colon >= 0 && line.substring(0, colon).strip().equals("palace-encrypted")) {
				// 合并重复标记，解密时移除全部标记；不重序列化其他字段。
				if (encrypted && !inserted) {
					result.append("palace-encrypted: v1").append(frontMatter, end, next);
					inserted = true;
				}
			} else {
				result.append(frontMatter, start, next);
			}
			start = next;
		}
		throw new IllegalArgumentException("front matter 缺少结束标记");
	}

	private String relativePath(Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private static boolean isMarkdown(String name) {
		return name.toLowerCase(Locale.ROOT).endsWith(".md");
	}

	private static void writeAtomically(Path file, String text) throws IOException {
		Path temporary = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
		try {
			Files.writeString(temporary, text, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
