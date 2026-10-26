package com.memorypalace.core.vault;

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

/**
 * 笔记库：一个装着 .md 文件的普通文件夹（默认 saves/&lt;世界&gt;/memory-palace/）。
 * 世界里的笔记书只存 UUID，靠这里把 UUID 翻译回文件。
 * 线程安全：所有公开方法可以从游戏的服务端线程和客户端线程同时调用。
 */
public final class Vault {
	private final Path root;
	private Map<UUID, NoteSummary> index = Map.of();
	private List<NoteSummary> notes = List.of();

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
		List<NoteSummary> newNotes = new ArrayList<>();
		for (Path file : files) {
			String text;
			try {
				text = Files.readString(file, StandardCharsets.UTF_8);
			} catch (IOException e) {
				continue;
			}
			FrontMatter.Parsed parsed = FrontMatter.parse(text);
			UUID id = parsed.id();
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
			String name = file.getFileName().toString();
			String title = parsed.body().lines()
					.filter(line -> line.startsWith("# "))
					.map(line -> line.substring(2).strip())
					.findFirst().orElse(name.substring(0, name.length() - 3));
			NoteSummary note = new NoteSummary(id, relativePath(file), title);
			newIndex.put(id, note);
			newNotes.add(note);
		}
		index = Map.copyOf(newIndex);
		notes = List.copyOf(newNotes);
	}

	/** 所有笔记，按 relativePath 字典序排序。 */
	public synchronized List<NoteSummary> list() {
		return notes;
	}

	/**
	 * 某个文件夹里"直接"的笔记（不含子文件夹里的），按 relativePath 排序。
	 * folder 用 "/" 分隔，"" 表示根目录；前后的 "/" 忽略。
	 */
	public synchronized List<NoteSummary> listFolder(String folder) {
		int start = 0;
		int end = folder.length();
		while (start < end && folder.charAt(start) == '/') {
			start++;
		}
		while (end > start && folder.charAt(end - 1) == '/') {
			end--;
		}
		String normalized = folder.substring(start, end);
		return notes.stream().filter(note -> note.folder().equals(normalized)).toList();
	}

	public synchronized Optional<NoteSummary> find(UUID id) {
		return id == null ? Optional.empty() : Optional.ofNullable(index.get(id));
	}

	/**
	 * 按相对路径找笔记，例如 "线性代数/特征值.md"；可以省略 ".md"。分隔符 "/" 和 "\" 都接受。
	 * 路径如果跑到笔记库外面（含 ".."、绝对路径等）一律返回 empty，绝不抛异常。
	 */
	public synchronized Optional<NoteSummary> resolve(String relativePath) {
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
			String requested = relativePath(target);
			boolean hasExtension = isMarkdown(requested);
			return notes.stream().filter(note -> {
				String candidate = note.relativePath();
				if (hasExtension) {
					return candidate.substring(0, candidate.length() - 3)
							.equals(requested.substring(0, requested.length() - 3));
				}
				return candidate.substring(0, candidate.length() - 3).equals(requested);
			}).findFirst();
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
		if (id == null) {
			return Optional.empty();
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			NoteSummary note = index.get(id);
			if (note != null) {
				try {
					FrontMatter.Parsed parsed = FrontMatter.parse(
							Files.readString(root.resolve(note.relativePath()), StandardCharsets.UTF_8));
					if (id.equals(parsed.id())) {
						return Optional.of(parsed.body());
					}
				} catch (NoSuchFileException e) {
					// 旧路径消失后重新扫描一次，以 ID 追踪改名后的文件。
				}
			}
			if (attempt == 0) {
				scan();
			}
		}
		return Optional.empty();
	}

	/** 笔记文件的绝对路径（给"用外部编辑器打开"按钮用）。 */
	public synchronized Optional<Path> pathOf(UUID id) {
		return find(id).map(note -> root.resolve(note.relativePath()));
	}

	/** writeBody 的结果。 */
	public enum WriteResult {
		/** 写好了。 */
		OK,
		/** 文件在打开编辑器之后被别人（外部编辑器）改过，没有写入。 */
		CONFLICT,
		/** 找不到这篇笔记（被删了）。 */
		MISSING
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
	 * - expectedHash 不为 null 时：先读出当前正文算 hash，和 expectedHash 不一样 → 返回 CONFLICT，不写
	 * - expectedHash 为 null：强制覆盖（用户在冲突提示里选了"覆盖"）
	 * - 原文件用 "\r\n" 换行的，新正文里的 "\n" 也换成 "\r\n"；原文件用 "\n" 的保持 "\n"
	 * - 原子写（临时文件 + move），UTF-8，不写 BOM
	 * - 写完后更新索引（标题可能变了），list()/find() 立刻能看到新标题
	 * - 找不到笔记 / 文件已被删 → MISSING（会先像 readBody 那样重新扫描一次再判断）
	 */
	public synchronized WriteResult writeBody(UUID id, String newBody, String expectedHash) throws IOException {
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
						if (expectedHash != null && !hash(parsed.body()).equals(expectedHash)) {
							return WriteResult.CONFLICT;
						}
						String newline = text.contains("\r\n") ? "\r\n" : "\n";
						String body = newBody.replace("\r\n", "\n").replace("\n", newline);
						// 正文是原文的后缀，直接截取可保留字段格式、注释和空白。
						String frontMatter = text.substring(text.startsWith("\uFEFF") ? 1 : 0,
								text.length() - parsed.body().length());
						if (!body.isEmpty() && !frontMatter.endsWith("\n")) {
							frontMatter += newline;
						}
						writeAtomically(file, frontMatter + body);
						scan();
						return WriteResult.OK;
					}
				}
			}
			if (attempt == 0) {
				scan();
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
	public synchronized NoteSummary createNote(String relativePath, String body) throws IOException {
		if (relativePath == null || relativePath.isEmpty()) {
			throw new IllegalArgumentException("笔记路径不能为空");
		}
		String input = relativePath.replace('\\', '/');
		if (input.startsWith("/")) {
			throw new IllegalArgumentException("笔记路径不能是绝对路径");
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
		if (!isMarkdown(input)) {
			input += ".md";
		}
		Path file;
		try {
			Path path = root.getFileSystem().getPath(input);
			if (path.isAbsolute() || path.getRoot() != null) {
				throw new IllegalArgumentException("笔记路径不能是绝对路径");
			}
			file = root.resolve(path).normalize();
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("笔记路径不合法", e);
		}
		if (!file.startsWith(root) || file.equals(root)) {
			throw new IllegalArgumentException("笔记路径不能超出笔记库");
		}
		Path ancestor = file.getParent();
		while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
			ancestor = ancestor.getParent();
		}
		if (!ancestor.toRealPath().startsWith(root.toRealPath())) {
			throw new IllegalArgumentException("笔记路径不能通过链接超出笔记库");
		}
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("笔记已存在");
		}
		String name = file.getFileName().toString();
		String newBody = body.isEmpty() ? "# " + name.substring(0, name.length() - 3) + "\n\n" : body;
		UUID id;
		do {
			id = UUID.randomUUID();
		} while (index.containsKey(id));
		String text = "---\npalace-id: " + id + "\n---\n" + newBody;
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
		scan();
		return find(id).orElseThrow(() -> new IOException("新笔记未能加入索引"));
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
