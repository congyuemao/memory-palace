package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把笔记库打包成 zip 备份到存档外面，防止删世界连笔记一起删掉。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class VaultBackup {
	// 新文件名用 UTC：不受时区、夏令时影响，只会一直往前走（本地时间会往回拨，按名字排序就会把最新的当成最旧的删掉）
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
	private static final Pattern BACKUP_NAME = Pattern.compile(
			"([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}-[0-9]{2}-[0-9]{2})Z(?:_([2-9]|[1-9][0-9]+))?\\.zip");
	private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

	/** 一个已有备份和它的真实时刻（同一秒的再按序号排）。 */
	private record Existing(Path file, java.time.Instant time, int suffix) {}

	private VaultBackup() {}

	/**
	 * 备份 vaultRoot 整个文件夹到 backupDir/&lt;时间&gt;.zip。要求：
	 * - 文件名格式 "yyyy-MM-dd'T'HH-mm-ss'Z'.zip"，时间取自 clock、一律用 UTC；同一秒内已存在同名文件时加 "_2"、"_3"…
	 * - zip 里的路径相对 vaultRoot，用 "/" 分隔，保留子文件夹；空文件夹不用保留
	 * - 包括隐藏文件夹（.palace/ 里的位置记录也要备份），但跳过名字以 ".palace-" 开头、".tmp" 结尾的临时文件
	 * - 原子写入：先写 backupDir 里的临时文件，写完再 move 成正式文件名；中途失败不能留下正式文件名的半成品，也不留临时文件
	 * - backupDir 不存在时自动创建
	 * - 如果打包结果与最新一个已有备份的内容完全相同（文件路径集合和每个文件的字节都一样），则不备份，返回 empty。
	 *   只按内容判断、不看修改时间：迁入带旧修改时间的笔记、改名、删除、改完还原修改时间都必须能备份上
	 * - 笔记库为空（没有任何文件）时不备份，返回 empty
	 * - 备份完成后清理：backupDir 里符合上面文件名格式的 .zip 只保留最新的 keep 个（按文件名表示的时刻排序，旧的删掉）；
	 *   不符合格式的文件一律不动
	 * - 单个文件读取失败：跳过它继续（不要让一个被占用的文件毁掉整个备份）
	 * @return 新建的备份文件；没有备份时为 empty
	 */
	public static synchronized Optional<Path> backup(Path vaultRoot, Path backupDir, int keep, Clock clock) throws IOException {
		if (keep < 1) {
			throw new IllegalArgumentException("keep must be positive");
		}
		Files.createDirectories(backupDir);
		List<Path> backups = listBackups(backupDir);
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(vaultRoot, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (attrs.isRegularFile() && !(name.startsWith(".palace-") && name.endsWith(".tmp"))) {
					files.add(file);
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
				if (file.equals(vaultRoot)) {
					throw error;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		if (files.isEmpty()) {
			return Optional.empty();
		}
		String timestamp = TIMESTAMP.format(clock.instant().atZone(java.time.ZoneOffset.UTC));
		Path target = backupDir.resolve(timestamp + ".zip");
		for (int suffix = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); suffix++) {
			target = backupDir.resolve(timestamp + "_" + suffix + ".zip");
		}
		Path temporary = Files.createTempFile(backupDir, ".palace-", ".tmp");
		try {
			int count = 0;
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
				for (Path file : files) {
					byte[] content;
					try {
						content = Files.readAllBytes(file);
					} catch (IOException e) {
						continue;
					}
					// 读取完整后再加入 ZIP，避免读取失败留下半个条目。
					zip.putNextEntry(new ZipEntry(vaultRoot.relativize(file).toString().replace('\\', '/')));
					zip.write(content);
					zip.closeEntry();
					count++;
				}
			}
			if (count == 0) {
				return Optional.empty();
			}
			// 是否变化只看内容：修改时间可能在未来，也可能比上次备份还旧（迁入、改名、删除、还原时间）。
			if (!backups.isEmpty() && sameContents(temporary, backups.getLast())) {
				return Optional.empty();
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
		backups = listBackups(backupDir);
		for (int i = 0; i < backups.size() - keep; i++) {
			Files.delete(backups.get(i));
		}
		return Optional.of(target);
	}

	private static boolean sameContents(Path first, Path second) {
		try (ZipFile left = new ZipFile(first.toFile()); ZipFile right = new ZipFile(second.toFile())) {
			if (left.size() != right.size()) {
				return false;
			}
			var entries = left.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				ZipEntry other = right.getEntry(entry.getName());
				if (other == null || entry.getSize() != other.getSize()) {
					return false;
				}
				try (var input = left.getInputStream(entry); var previous = right.getInputStream(other)) {
					byte[] block;
					do {
						block = input.readNBytes(8192);
						if (!Arrays.equals(block, previous.readNBytes(8192))) {
							return false;
						}
					} while (block.length != 0);
				}
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** 已有备份，按文件名表示的时刻从旧到新排（同一秒的按序号）。 */
	private static List<Path> listBackups(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
					.map(VaultBackup::parse)
					.filter(Objects::nonNull)
					.sorted(Comparator.comparing(Existing::time).thenComparingInt(Existing::suffix))
					.map(Existing::file)
					.toList();
		}
	}

	/** 认出备份文件名并换算成时刻；不是备份文件返回 null。 */
	private static Existing parse(Path file) {
		Matcher m = BACKUP_NAME.matcher(file.getFileName().toString());
		if (!m.matches()) {
			return null;
		}
		try {
			var time = java.time.LocalDateTime.parse(m.group(1) + "_" + m.group(2), PARSE).toInstant(java.time.ZoneOffset.UTC);
			return new Existing(file, time, m.group(3) == null ? 1 : Integer.parseInt(m.group(3)));
		} catch (java.time.DateTimeException | NumberFormatException e) {
			// 名字长得像但日期不合法（比如 13 月）：不当成备份，也就不会被删
		}
		return null;
	}
}
