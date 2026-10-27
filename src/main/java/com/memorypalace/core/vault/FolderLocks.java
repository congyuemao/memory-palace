package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 文件夹独立密码的"锁"：被锁的文件夹里有一个隐藏文件 .palace-lock，内容是用文件夹密码锁住的文件夹钥匙
 * （KeyRing.createFolder 的格式）。锁放在文件夹里，在外面移动、改名文件夹，锁跟着走；子文件夹一起受保护。
 * 这里只管找锁、读写锁文件，不碰密码和钥匙（加解密全在客户端）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class FolderLocks {
	public static final String FILE = ".palace-lock";
	/** 锁文件最大这么多字符（和钥匙圈一样）。 */
	public static final int MAX_CHARS = 4096;

	private FolderLocks() {}

	/**
	 * folder 自己或它往上最近的、被锁的文件夹（"/" 分隔的相对路径）；都没锁时 empty。根目录不能锁，不算。
	 * folder 用 "/" 分隔，"" 表示根目录。
	 */
	public static Optional<String> lockedFolder(Vault vault, String folder) {
		String f = folder;
		while (!f.isEmpty()) {
			if (Files.isRegularFile(lockFile(vault, f), LinkOption.NOFOLLOW_LINKS)) {
				return Optional.of(f);
			}
			int slash = f.lastIndexOf('/');
			f = slash < 0 ? "" : f.substring(0, slash);
		}
		return Optional.empty();
	}

	/** 这篇笔记所在的被锁文件夹（见 lockedFolder）。 */
	public static Optional<String> lockedFolderOf(Vault vault, NoteSummary note) {
		return lockedFolder(vault, note.folder());
	}

	/** 读锁文件；没有 → empty。 */
	public static Optional<String> read(Vault vault, String folder) throws IOException {
		try {
			return Optional.of(Files.readString(lockFile(vault, folder), StandardCharsets.UTF_8));
		} catch (NoSuchFileException e) {
			return Optional.empty();
		}
	}

	/** write 的结果。 */
	public enum WriteResult { OK, CONFLICT, MISSING_FOLDER }

	/**
	 * 写锁文件。expectedHash 是调用方以为的当前内容的指纹（Vault.hash；还没有锁时为 ""），对不上 → CONFLICT，不写：
	 * 防止过期的写入覆盖新的锁（覆盖了，用旧锁加密的笔记就再也打不开了）。文件夹不存在 → MISSING_FOLDER。原子写入。
	 */
	public static synchronized WriteResult write(Vault vault, String folder, String expectedHash, String content) throws IOException {
		Path dir = folderPath(vault, folder);
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			return WriteResult.MISSING_FOLDER;
		}
		Path file = dir.resolve(FILE);
		String current;
		try {
			current = Files.readString(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			current = null;
		}
		if (!(current == null ? "" : Vault.hash(current)).equals(expectedHash)) {
			return WriteResult.CONFLICT;
		}
		Path tmp = Files.createTempFile(dir, ".palace-", ".tmp");
		try {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
		return WriteResult.OK;
	}

	/** 文件夹路径必须在笔记库里、不是根目录、每一级都不以 "." 开头、不含 ".."；否则 IllegalArgumentException。 */
	private static Path folderPath(Vault vault, String folder) {
		if (folder == null || folder.isEmpty() || folder.startsWith("/") || folder.endsWith("/") || folder.contains("\\")) {
			throw new IllegalArgumentException("文件夹路径不合法");
		}
		for (String part : folder.split("/", -1)) {
			if (part.isEmpty() || part.startsWith(".") || part.contains(":")) {
				throw new IllegalArgumentException("文件夹路径不合法");
			}
		}
		Path root = vault.root();
		Path dir = root.resolve(folder).normalize();
		if (!dir.startsWith(root) || dir.equals(root)) {
			throw new IllegalArgumentException("文件夹路径不合法");
		}
		return dir;
	}

	private static Path lockFile(Vault vault, String folder) {
		return folderPath(vault, folder).resolve(FILE);
	}
}
