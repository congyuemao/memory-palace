package com.memorypalace.core.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 把笔记库备份到存档外面，防止删世界连笔记一起删掉。增量备份：内容相同的文件只存一份。
 * <pre>
 * backupDir/objects/ab/abcdef….gz          文件内容（gzip），按内容的 SHA-256 命名，同样的内容只存一份
 * backupDir/snapshots/2026-10-27T03-04-05Z.txt   一次备份的清单：每行"SHA-256 字节数 相对路径"
 * backupDir/最新备份.zip                     最新一次备份的完整 ZIP（每次覆盖），不用工具也能直接解压
 * </pre>
 * 每次备份只写新出现的内容和一张清单；没改过的文件不再重复保存。旧清单按数量淘汰，淘汰后没有任何清单用到的内容一起删掉。
 * backupDir 里的其他文件（比如以前版本留下的 ZIP 备份）一律不动。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class VaultBackup {
	private static final String HEADER = "memory-palace-backup 1";
	// 清单名用 UTC：不受时区、夏令时影响，只会一直往前走（本地时间会往回拨，按名字排序就会把最新的当成最旧的删掉）
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.ROOT);
	private static final Pattern SNAPSHOT_NAME = Pattern.compile(
			"([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}-[0-9]{2}-[0-9]{2})Z(?:_([2-9]|[1-9][0-9]+))?\\.txt");
	private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	/** 最新一次备份的完整 ZIP。 */
	public static final String LATEST_ZIP = "最新备份.zip";
	// 回收站：恢复备份时不动它，也不从备份里恢复它
	private static final String TRASH = ".palace/trash/";

	/** 一次备份：id 是清单文件名去掉 .txt，time 是它的时刻（同一秒的再按序号排）。 */
	public record Snapshot(String id, Instant time, int suffix, Path manifest) {}

	/** 清单里的一个文件。 */
	private record Entry(String hash, long size) {}

	/** 恢复的结果：恢复了几个文件；broken 是内容缺失或损坏、没能恢复的相对路径。 */
	public record Restored(int files, List<String> broken) {}

	/** 恢复回笔记库的结果：写回了几个文件，移进回收站几个（当前有、备份里没有或内容不同的）。 */
	public record RestoredInPlace(int written, int movedToTrash, Path trash) {}

	private VaultBackup() {}

	/** 源文件或目录无法完整读取；本次备份已放弃，不会清理已有备份。 */
	public static final class IncompleteBackupException extends IOException {
		private IncompleteBackupException(Path source, IOException cause) {
			super("无法完整读取笔记库：" + source, cause);
		}
	}

	/**
	 * 备份 vaultRoot 整个文件夹到 backupDir。要求：
	 * - 清单名 "yyyy-MM-dd'T'HH-mm-ss'Z'.txt"，时间取自 clock、一律用 UTC；同一秒内已存在同名清单时加 "_2"、"_3"…
	 * - 路径相对 vaultRoot，用 "/" 分隔；空文件夹不保留
	 * - 包括隐藏文件夹（.palace/ 里的位置记录、历史版本也要备份），但跳过名字以 ".palace-" 开头、".tmp" 结尾的临时文件
	 * - 内容和最新一份清单完全相同（路径集合和每个文件的内容都一样）时不备份，返回 empty。只看内容、不看修改时间
	 * - 首次备份时笔记库为空，返回 empty；已有备份后删光了所有文件则保存一份空清单
	 * - 内容和清单都先写临时文件再改名：中途失败不留下半成品
	 * - 备份完成后只保留最新的 keep 份清单，再删掉没有任何清单用到的内容；有清单读不出来时不删内容（宁可多留）
	 * - 任何源文件读取或目录遍历失败：放弃本次备份，不写清单、不淘汰已有备份
	 * @return 新清单；内容未变化或首次备份时笔记库为空则为 empty（失败绝不返回 empty）
	 * @throws IncompleteBackupException 源文件或目录无法完整读取
	 */
	public static synchronized Optional<Path> backup(Path vaultRoot, Path backupDir, int keep, Clock clock) throws IOException {
		if (keep < 1) {
			throw new IllegalArgumentException("keep must be positive");
		}
		Path snapshotsDir = backupDir.resolve("snapshots");
		Path objectsDir = backupDir.resolve("objects");
		Files.createDirectories(snapshotsDir);
		Files.createDirectories(objectsDir);
		List<Snapshot> snapshots = snapshots(backupDir);
		List<Path> files = listFiles(vaultRoot);
		if (files.isEmpty() && snapshots.isEmpty()) {
			return Optional.empty();
		}
		// 逐个读文件、算指纹，没存过的内容马上存进 objects（失败时留下的内容没有清单引用，下次成功备份后会被清理）
		TreeMap<String, Entry> entries = new TreeMap<>();
		for (Path file : files) {
			byte[] content;
			try {
				content = Files.readAllBytes(file);
			} catch (IOException e) {
				throw new IncompleteBackupException(file, e);
			}
			String hash = sha256(content);
			storeObject(objectsDir, hash, content);
			entries.put(vaultRoot.relativize(file).toString().replace('\\', '/'), new Entry(hash, content.length));
		}
		if (!snapshots.isEmpty() && entries.equals(readManifestOrNull(snapshots.getLast().manifest()))) {
			if (!Files.exists(backupDir.resolve(LATEST_ZIP))) {
				writeLatestZip(backupDir, entries);
			}
			return Optional.empty();
		}
		String timestamp = TIMESTAMP.format(clock.instant().atZone(java.time.ZoneOffset.UTC));
		Path target = snapshotsDir.resolve(timestamp + ".txt");
		for (int suffix = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); suffix++) {
			target = snapshotsDir.resolve(timestamp + "_" + suffix + ".txt");
		}
		StringBuilder manifest = new StringBuilder(HEADER).append('\n');
		entries.forEach((path, entry) -> manifest.append(entry.hash()).append(' ').append(entry.size()).append(' ')
				.append(escape(path)).append('\n'));
		writeAtomically(snapshotsDir, target, manifest.toString().getBytes(StandardCharsets.UTF_8));
		writeLatestZip(backupDir, entries);

		snapshots = snapshots(backupDir);
		for (int i = 0; i < snapshots.size() - keep; i++) {
			Files.delete(snapshots.get(i).manifest());
		}
		collectGarbage(backupDir);
		return Optional.of(target);
	}

	/** backupDir 里的备份，从旧到新。没有时为空列表。 */
	public static List<Snapshot> snapshots(Path backupDir) throws IOException {
		Path dir = backupDir.resolve("snapshots");
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (var files = Files.list(dir)) {
			return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
					.map(VaultBackup::parse)
					.filter(Objects::nonNull)
					.sorted(Comparator.comparing(Snapshot::time).thenComparingInt(Snapshot::suffix))
					.toList();
		}
	}

	/**
	 * 把某一次备份恢复到 target（必须还不存在，不覆盖任何东西）。每个文件都核对指纹：
	 * 内容缺失、损坏，或者清单里的路径想跑出 target 的，跳过并记在 broken 里，其余照常恢复。
	 * @throws IOException 清单读不出来、target 已存在、写入失败
	 */
	public static Restored restore(Path backupDir, String snapshotId, Path target) throws IOException {
		Snapshot snapshot = snapshots(backupDir).stream().filter(s -> s.id().equals(snapshotId)).findFirst()
				.orElseThrow(() -> new IOException("找不到这次备份：" + snapshotId));
		TreeMap<String, Entry> entries = readManifestOrNull(snapshot.manifest());
		if (entries == null) {
			throw new IOException("备份清单已损坏：" + snapshot.manifest());
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("目标文件夹已存在：" + target);
		}
		Files.createDirectories(target);
		Path root = target.toAbsolutePath().normalize();
		int restored = 0;
		List<String> broken = new ArrayList<>();
		for (var e : entries.entrySet()) {
			Path out = root.resolve(e.getKey()).normalize();
			byte[] content = readObject(backupDir.resolve("objects"), e.getValue().hash());
			if (!out.startsWith(root) || out.equals(root) || content == null || content.length != e.getValue().size()
					|| !sha256(content).equals(e.getValue().hash())) {
				broken.add(e.getKey());
				continue;
			}
			Files.createDirectories(out.getParent());
			Files.write(out, content);
			restored++;
		}
		return new Restored(restored, broken);
	}

	/**
	 * 把笔记库恢复成某一次备份的样子（调用方应该先 backup 一次当前内容，恢复错了还能再恢复回来）：
	 * - 先核对这次备份用到的全部内容，有缺失或损坏就什么都不动，抛 IOException
	 * - 当前有、备份里没有或者内容不同的文件，移进 .palace/trash/&lt;stamp&gt;/（保持原来的相对路径），不直接删
	 * - 再把备份里的文件写回去；回收站本身不动，也不从备份里恢复
	 */
	public static synchronized RestoredInPlace restoreInto(Path backupDir, String snapshotId, Path vaultRoot, String stamp)
			throws IOException {
		Snapshot snapshot = snapshots(backupDir).stream().filter(s -> s.id().equals(snapshotId)).findFirst()
				.orElseThrow(() -> new IOException("找不到这次备份：" + snapshotId));
		TreeMap<String, Entry> entries = readManifestOrNull(snapshot.manifest());
		if (entries == null) {
			throw new IOException("备份清单已损坏：" + snapshot.manifest());
		}
		entries.keySet().removeIf(path -> path.startsWith(TRASH));
		Path root = vaultRoot.toAbsolutePath().normalize();
		TreeMap<String, byte[]> contents = new TreeMap<>();
		for (var e : entries.entrySet()) {
			Path out = root.resolve(e.getKey()).normalize();
			byte[] content = readObject(backupDir.resolve("objects"), e.getValue().hash());
			if (!out.startsWith(root) || out.equals(root) || content == null || content.length != e.getValue().size()
					|| !sha256(content).equals(e.getValue().hash())) {
				throw new IOException("备份内容缺失或已损坏，未做任何改动：" + e.getKey());
			}
			contents.put(e.getKey(), content);
		}
		Path trash = root.resolve(".palace").resolve("trash").resolve(stamp);
		int moved = 0;
		for (Path file : listFiles(root)) {
			String rel = root.relativize(file).toString().replace('\\', '/');
			if (rel.startsWith(TRASH)) {
				continue;
			}
			byte[] wanted = contents.get(rel);
			if (wanted != null && java.util.Arrays.equals(wanted, Files.readAllBytes(file))) {
				contents.remove(rel); // 一样的：不用动
				continue;
			}
			Path to = trash.resolve(rel);
			Files.createDirectories(to.getParent());
			Files.move(file, to);
			moved++;
		}
		for (var e : contents.entrySet()) {
			Path out = root.resolve(e.getKey());
			Files.createDirectories(out.getParent());
			writeAtomically(out.getParent(), out, e.getValue());
		}
		return new RestoredInPlace(contents.size(), moved, trash);
	}

	// ---- 内部 ----

	/** 把这份清单打成完整 ZIP，覆盖 LATEST_ZIP（先写临时文件再改名）。 */
	private static void writeLatestZip(Path backupDir, TreeMap<String, Entry> entries) throws IOException {
		Path temporary = Files.createTempFile(backupDir, ".palace-", ".tmp");
		try {
			try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(temporary))) {
				for (var e : entries.entrySet()) {
					byte[] content = readObject(backupDir.resolve("objects"), e.getValue().hash());
					if (content == null) {
						throw new IOException("备份内容缺失：" + e.getKey());
					}
					zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
					zip.write(content);
					zip.closeEntry();
				}
			}
			try {
				Files.move(temporary, backupDir.resolve(LATEST_ZIP), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, backupDir.resolve(LATEST_ZIP), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static List<Path> listFiles(Path vaultRoot) throws IOException {
		List<Path> files = new ArrayList<>();
		Files.walkFileTree(vaultRoot, new SimpleFileVisitor<>() {
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
				throw new IncompleteBackupException(file, error);
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) {
					throw new IncompleteBackupException(directory, error);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return files;
	}

	private static Path objectPath(Path objectsDir, String hash) {
		return objectsDir.resolve(hash.substring(0, 2)).resolve(hash + ".gz");
	}

	/** 没存过就压缩存进去（先写临时文件再改名）；已经有了就什么都不做。 */
	private static void storeObject(Path objectsDir, String hash, byte[] content) throws IOException {
		Path path = objectPath(objectsDir, hash);
		if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.createDirectories(path.getParent());
		var buffer = new java.io.ByteArrayOutputStream();
		try (OutputStream gzip = new GZIPOutputStream(buffer)) {
			gzip.write(content);
		}
		writeAtomically(path.getParent(), path, buffer.toByteArray());
	}

	/** 读出一份内容；没有或者解压失败时为 null。 */
	private static byte[] readObject(Path objectsDir, String hash) {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(objectPath(objectsDir, hash)))) {
			return in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeAtomically(Path dir, Path target, byte[] bytes) throws IOException {
		Path temporary = Files.createTempFile(dir, ".palace-", ".tmp");
		try {
			Files.write(temporary, bytes);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/** 删掉没有任何清单用到的内容。有清单读不出来就不删（不知道它用到了什么）。 */
	private static void collectGarbage(Path backupDir) throws IOException {
		Set<String> used = new HashSet<>();
		for (Snapshot snapshot : snapshots(backupDir)) {
			TreeMap<String, Entry> entries = readManifestOrNull(snapshot.manifest());
			if (entries == null) {
				return;
			}
			entries.values().forEach(e -> used.add(e.hash()));
		}
		Path objectsDir = backupDir.resolve("objects");
		try (var dirs = Files.list(objectsDir)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				try (var objects = Files.list(dir)) {
					for (Path object : objects.toList()) {
						String name = object.getFileName().toString();
						if (name.endsWith(".gz") && HASH.matcher(name.substring(0, name.length() - 3)).matches()
								&& !used.contains(name.substring(0, name.length() - 3))) {
							Files.deleteIfExists(object);
						}
					}
				}
			}
		}
	}

	/** 读清单；格式不对或读不出来时为 null。 */
	private static TreeMap<String, Entry> readManifestOrNull(Path manifest) {
		List<String> lines;
		try {
			lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
		if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
			return null;
		}
		TreeMap<String, Entry> entries = new TreeMap<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isEmpty()) {
				continue;
			}
			String[] parts = line.split(" ", 3);
			if (parts.length != 3 || !HASH.matcher(parts[0]).matches()) {
				return null;
			}
			try {
				entries.put(unescape(parts[2]), new Entry(parts[0], Long.parseLong(parts[1])));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return entries;
	}

	// 路径里的反斜杠、换行转义成两个字符，清单保持一行一个文件
	private static String escape(String path) {
		return path.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String unescape(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '\\') {
				out.append(c);
				continue;
			}
			if (++i >= text.length()) {
				throw new IllegalArgumentException("清单里的路径格式不对");
			}
			out.append(switch (text.charAt(i)) {
				case 'n' -> '\n';
				case 'r' -> '\r';
				case '\\' -> '\\';
				default -> throw new IllegalArgumentException("清单里的路径格式不对");
			});
		}
		return out.toString();
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 认出清单文件名并换算成时刻；不是清单返回 null。 */
	private static Snapshot parse(Path file) {
		Matcher m = SNAPSHOT_NAME.matcher(file.getFileName().toString());
		if (!m.matches()) {
			return null;
		}
		try {
			var time = java.time.LocalDateTime.parse(m.group(1) + "_" + m.group(2), PARSE).toInstant(java.time.ZoneOffset.UTC);
			String name = file.getFileName().toString();
			return new Snapshot(name.substring(0, name.length() - 4), time, m.group(3) == null ? 1 : Integer.parseInt(m.group(3)), file);
		} catch (java.time.DateTimeException | NumberFormatException e) {
			// 名字长得像但日期不合法（比如 13 月）：不当成备份，也就不会被删
		}
		return null;
	}
}
