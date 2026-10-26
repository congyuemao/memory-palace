package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
