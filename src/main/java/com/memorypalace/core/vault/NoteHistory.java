package com.memorypalace.core.vault;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 历史版本：游戏内保存一篇笔记时，把保存前的正文存一份到 .palace/history/&lt;笔记ID&gt;/&lt;UTC 时间&gt;.md。
 * - 每篇只留最新的 KEEP（5）份
 * - 同一篇 10 分钟内的多次保存只留一份（留这段编辑开始前的样子），不然编辑器连着保存几次就把 5 份刷完了；
 *   还原时不受这个限制（还原前的样子一定留一份，可以反悔）
 * - 只存正文（加密笔记就是密文，不解密）
 * - 明文笔记被加密后，清掉它以前的明文历史：不能在笔记加密后还躺在硬盘上（正文写好之后才清，
 *   之后每次保存加密笔记都再清一遍，上次没清干净的接着清，见 purgePlain）
 * - 在外部编辑器里改的不记（由 VaultBackup 的整库备份管）
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteHistory {
	public static final int KEEP = 5;
	static final Duration MERGE = Duration.ofMinutes(10);
	// 文件名用 UTC、精确到毫秒：按名字排序就是按时间排序
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", Locale.ROOT)
			.withZone(ZoneOffset.UTC);
	private static final Pattern VERSION_ID = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}\\.[0-9]{3}Z");

	/** 一份历史版本：id（文件名去掉 .md）、保存的时刻、字数（char 数）、是不是密文。 */
	public record Version(String id, Instant time, long chars, boolean encrypted) {}

	private final Path root;
	private final Clock clock;

	/** vaultRoot：笔记库根目录（历史放在它下面的 .palace/history/）。 */
	public NoteHistory(Path vaultRoot, Clock clock) {
		this.root = vaultRoot.resolve(".palace").resolve("history");
		this.clock = clock;
	}

	/** 版本 ID 格式对不对（命令里传进来的，防止路径穿越）。 */
	public static boolean validId(String id) {
		return id != null && VERSION_ID.matcher(id).matches();
	}

	/**
	 * 一篇笔记正文要从 oldBody 改成 newBody 了：按规则存一份 oldBody。
	 * force = 还原：不管 10 分钟合并，一定存。存不下来不抛异常（不能因为历史存不了就不让保存），返回 false。
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
			List<Version> versions = list(noteId);
			if (Ciphertext.isEncrypted(oldBody)) {
				// 已经是加密笔记：顺手清掉漏网的明文历史；清不掉也不让它参与下面的"10 分钟合并"、把这次的密文版本挤掉
				purgePlain(noteId);
				versions = versions.stream().filter(Version::encrypted).toList();
			}
			Instant now = clock.instant();
			if (!versions.isEmpty()) {
				Version newest = versions.get(0);
				if (!force && Duration.between(newest.time(), now).compareTo(MERGE) < 0) {
					return true; // 同一段编辑：已经留过这段开始前的样子了
				}
				Optional<String> newestBody = read(noteId, newest.id());
				if (newestBody.isPresent() && newestBody.get().equals(oldBody)) {
					return true; // 和最新一份一样，不重复存
				}
			}
			Path dir = root.resolve(noteId.toString());
			Files.createDirectories(dir);
			// 同一毫秒已有（几乎不可能）就往后挪一毫秒，保证名字唯一、按名字排序仍是按时间
			Instant stamp = now;
			while (Files.exists(dir.resolve(NAME.format(stamp) + ".md"))) {
				stamp = stamp.plusMillis(1);
			}
			Path temporary = Files.createTempFile(dir, ".palace-", ".tmp");
			try {
				Files.writeString(temporary, oldBody, StandardCharsets.UTF_8);
				Path target = dir.resolve(NAME.format(stamp) + ".md");
				try {
					Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(temporary, target); // 文件系统不支持原子移动：普通移动（目标不存在，不会覆盖）
				}
			} finally {
				Files.deleteIfExists(temporary);
			}
			prune(noteId);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** 这篇的历史版本，最新的在前。没有就是空列表。 */
	public synchronized List<Version> list(UUID noteId) throws IOException {
		Path dir = root.resolve(noteId.toString());
		List<Version> out = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.md")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				String id = name.substring(0, name.length() - 3);
				if (!validId(id)) {
					continue;
				}
				try {
					Instant time = Instant.from(NAME.parse(id));
					String body = Files.readString(file, StandardCharsets.UTF_8);
					out.add(new Version(id, time, body.length(), Ciphertext.isEncrypted(body)));
				} catch (DateTimeParseException | IOException e) {
					// 读不了的跳过
				}
			}
		} catch (NoSuchFileException e) {
			return List.of();
		}
		out.sort(Comparator.comparing(Version::id).reversed());
		return out;
	}

	/** 读一份历史版本的正文。ID 格式不对、不存在 → empty。 */
	public synchronized Optional<String> read(UUID noteId, String versionId) throws IOException {
		if (!validId(versionId)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.readString(root.resolve(noteId.toString()).resolve(versionId + ".md"), StandardCharsets.UTF_8));
		} catch (NoSuchFileException e) {
			return Optional.empty();
		}
	}

	/**
	 * 删掉这篇的明文历史（笔记现在是加密的：不能还有明文副本躺在硬盘上）。
	 * 每次加密笔记保存成功后都调用：上次没删干净（文件被占用等），下次接着删。全删掉了返回 true。
	 */
	public synchronized boolean purgePlain(UUID noteId) {
		Path dir = root.resolve(noteId.toString());
		boolean ok = true;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				try {
					// 只留认得出来的密文版本；明文、认不出来的、写了一半的临时文件都删
					boolean keep = name.endsWith(".md") && validId(name.substring(0, name.length() - 3))
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

	// 只留最新的 KEEP 份
	private void prune(UUID noteId) throws IOException {
		List<Version> versions = list(noteId);
		Path dir = root.resolve(noteId.toString());
		for (int i = KEEP; i < versions.size(); i++) {
			Files.deleteIfExists(dir.resolve(versions.get(i).id() + ".md"));
		}
	}
}
