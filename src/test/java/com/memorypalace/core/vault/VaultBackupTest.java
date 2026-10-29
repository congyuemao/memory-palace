package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class VaultBackupTest {
	@TempDir
	Path dir;

	private static final Instant T0 = Instant.parse("2026-10-27T03:04:05Z");

	private Path vault() {
		return dir.resolve("vault");
	}

	private Path backups() {
		return dir.resolve("backups");
	}

	private static Clock at(Instant t) {
		return Clock.fixed(t, ZoneOffset.UTC);
	}

	private Path write(String rel, String content, Instant modified) throws IOException {
		Path p = vault().resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(p, FileTime.from(modified));
		return p;
	}

	private int restores;

	/** 把这次备份恢复到一个新文件夹，读出全部文件（相对路径 → 内容）。 */
	private Map<String, String> restored(Path manifest) throws IOException {
		String name = manifest.getFileName().toString();
		Path target = dir.resolve("restored-" + restores++);
		VaultBackup.Restored result = VaultBackup.restore(backups(), name.substring(0, name.length() - 4), target);
		assertEquals(List.of(), result.broken());
		Map<String, String> files = new java.util.TreeMap<>();
		try (Stream<Path> s = Files.walk(target)) {
			for (Path f : s.filter(Files::isRegularFile).toList()) {
				files.put(target.relativize(f).toString().replace('\\', '/'), Files.readString(f, StandardCharsets.UTF_8));
			}
		}
		return files;
	}

	private List<String> zipEntries(Path manifest) throws IOException {
		return new ArrayList<>(restored(manifest).keySet());
	}

	/** snapshots 文件夹里的清单名（不含临时文件就说明没有留下半成品）。 */
	private List<String> backupNames() throws IOException {
		Path snapshots = backups().resolve("snapshots");
		if (!Files.isDirectory(snapshots)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(snapshots)) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	/** objects 里存了几份内容。 */
	private long objectCount() throws IOException {
		try (Stream<Path> s = Files.walk(backups().resolve("objects"))) {
			return s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".gz")).count();
		}
	}

	@Test
	void backsUpEverythingExceptTempFiles() throws IOException {
		Instant old = T0.minusSeconds(3600);
		write("欢迎.md", "# 欢迎", old);
		write("线代/特征值.md", "# 特征值", old);
		write(".palace/locations.tsv", "x", old);
		write("线代/.palace-123.tmp", "半成品", old);

		Optional<Path> zip = VaultBackup.backup(vault(), backups(), 10, at(T0));
		assertTrue(zip.isPresent());
		assertEquals("2026-10-27T03-04-05Z.txt", zip.get().getFileName().toString());
		assertEquals(List.of(".palace/locations.tsv", "欢迎.md", "线代/特征值.md"), zipEntries(zip.get()));
		// 没有留下临时文件
		assertEquals(List.of("2026-10-27T03-04-05Z.txt"), backupNames());
	}

	@Test
	void skipsWhenNothingChangedOrEmpty() throws IOException {
		Files.createDirectories(vault());
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0)).isEmpty(), "空笔记库不备份");

		write("a.md", "A", T0.minusSeconds(3600));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0)).isPresent());
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).isEmpty(), "没改过不重复备份");

		// 改了文件（修改时间晚于上次备份）→ 再备份
		write("a.md", "A2", Instant.now().plusSeconds(3600));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).isPresent());
	}

	private String zipText(Path manifest, String entry) throws IOException {
		String text = restored(manifest).get(entry);
		assertNotNull(text, "备份里应有 " + entry);
		return text;
	}

	// 下面几种变化都不会让任何文件的修改时间晚于上次备份（迁入的旧笔记、改名、删除、改完还原时间），
	// 只看修改时间就会漏备份，必须比较内容。
	private static final Instant OLD = T0.minusSeconds(86400);

	private Path firstBackup() throws IOException {
		write("a.md", "A", OLD);
		write("线代/b.md", "BBBB", OLD);
		return VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
	}

	@Test
	void backsUpAddedFileWithOldTimestamp() throws IOException {
		firstBackup();
		write("迁入/旧笔记.md", "# 从别的笔记库搬来", OLD.minusSeconds(86400));
		Path zip = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of("a.md", "线代/b.md", "迁入/旧笔记.md"), zipEntries(zip));
		assertEquals("# 从别的笔记库搬来", zipText(zip, "迁入/旧笔记.md"));
	}

	@Test
	void backsUpRenamedFile() throws IOException {
		firstBackup();
		Files.move(vault().resolve("线代/b.md"), vault().resolve("线代/c.md"));
		Files.setLastModifiedTime(vault().resolve("线代/c.md"), FileTime.from(OLD));
		Path zip = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of("a.md", "线代/c.md"), zipEntries(zip));
		assertEquals("BBBB", zipText(zip, "线代/c.md"));
	}

	@Test
	void backsUpDeletion() throws IOException {
		firstBackup();
		Files.delete(vault().resolve("线代/b.md"));
		Path zip = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of("a.md"), zipEntries(zip));
	}

	@Test
	void backsUpContentChangeWithOldTimestamp() throws IOException {
		firstBackup();
		write("线代/b.md", "XXXX", OLD); // 长度相同、修改时间还原
		Path zip = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals("XXXX", zipText(zip, "线代/b.md"));
		write("a.md", "longer text", OLD); // 长度不同
		Path third = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).orElseThrow();
		assertEquals("longer text", zipText(third, "a.md"));
		assertEquals("XXXX", zipText(third, "线代/b.md"));
		// 内容没再变：不重复备份，哪怕修改时间被碰到了未来
		Files.setLastModifiedTime(vault().resolve("a.md"), FileTime.from(Instant.now().plusSeconds(3600)));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(180))).isEmpty());
		assertEquals(List.of("2026-10-27T03-04-05Z.txt", "2026-10-27T03-05-05Z.txt", "2026-10-27T03-06-05Z.txt"), backupNames());
	}

	@Test
	void sameSecondGetsSuffix() throws IOException {
		write("a.md", "A", T0.minusSeconds(3600));
		VaultBackup.backup(vault(), backups(), 10, at(T0));
		write("a.md", "A2", Instant.now().plusSeconds(3600));
		Optional<Path> second = VaultBackup.backup(vault(), backups(), 10, at(T0));
		assertEquals("2026-10-27T03-04-05Z_2.txt", second.orElseThrow().getFileName().toString());
	}

	@Test
	void keepsOnlyNewestAndLeavesOtherFilesAlone() throws IOException {
		Files.createDirectories(backups());
		Files.writeString(backups().resolve("说明.txt"), "别删我");
		Files.writeString(backups().resolve("2026-10-01T00-00-00Z.zip"), "以前版本的 ZIP 备份");
		for (int i = 0; i < 5; i++) {
			// 每次都让文件"比上一次备份新"
			write("a.md", "v" + i, Instant.now().plusSeconds(3600L * (i + 1)));
			assertTrue(VaultBackup.backup(vault(), backups(), 3, at(T0.plusSeconds(i))).isPresent());
		}
		assertEquals(List.of(
				"2026-10-27T03-04-07Z.txt",
				"2026-10-27T03-04-08Z.txt",
				"2026-10-27T03-04-09Z.txt"), backupNames());
		assertTrue(Files.exists(backups().resolve("说明.txt")), "别的文件不动");
		assertTrue(Files.exists(backups().resolve("2026-10-01T00-00-00Z.zip")), "以前的 ZIP 备份不动");
		assertEquals(3, objectCount(), "淘汰掉的备份用到的内容也删掉了");
	}

	@Test
	void timezoneChangeNeverDeletesTheNewestBackup() throws IOException {
		// 在墨尔本（UTC+11）做了两次备份，然后飞到中国（UTC+8），本地时间往回拨了 3 小时。
		// 以前用本地时间命名，新备份的名字排在前面，会被当成最旧的删掉。
		ZoneId melbourne = ZoneId.of("Australia/Melbourne");
		ZoneId shanghai = ZoneId.of("Asia/Shanghai");
		write("a.md", "1", Instant.now().plusSeconds(3600));
		VaultBackup.backup(vault(), backups(), 2, Clock.fixed(T0, melbourne));
		write("a.md", "2", Instant.now().plusSeconds(7200));
		VaultBackup.backup(vault(), backups(), 2, Clock.fixed(T0.plusSeconds(60), melbourne));
		write("a.md", "3", Instant.now().plusSeconds(10800));
		Path newest = VaultBackup.backup(vault(), backups(), 2, Clock.fixed(T0.plusSeconds(120), shanghai)).orElseThrow();

		assertTrue(Files.exists(newest), "最新的备份必须留下");
		assertEquals(List.of("2026-10-27T03-05-05Z.txt", "2026-10-27T03-06-05Z.txt"), backupNames());
	}

	@FunctionalInterface
	private interface IoAction {
		void run() throws IOException;
	}

	/**
	 * 使用真实权限拒绝；root 或既不支持 POSIX 权限、也不支持 ACL 的平台必须明确跳过，不能假通过。
	 * Windows（NTFS）没有 POSIX 权限：在最前面加一条"拒绝当前用户读数据"的 ACL（对目录就是不让列目录），测完恢复原样。
	 */
	private void withReadDenied(Path source, IoAction action) throws IOException {
		if (!Files.getFileStore(source).supportsFileAttributeView("posix")) {
			withAclReadDenied(source, action);
			return;
		}
		Set<PosixFilePermission> original = Files.getPosixFilePermissions(source);
		try {
			Files.setPosixFilePermissions(source, Set.of());
			assumeFalse(Files.isReadable(source), "请用非特权进程运行：当前进程可绕过读取权限");
			action.run();
		} finally {
			Files.setPosixFilePermissions(source, original);
		}
	}

	private void withAclReadDenied(Path source, IoAction action) throws IOException {
		AclFileAttributeView view = Files.getFileAttributeView(source, AclFileAttributeView.class);
		assumeTrue(view != null, "需要 POSIX 文件权限或 ACL");
		List<AclEntry> original = view.getAcl();
		AclEntry deny = AclEntry.newBuilder()
				.setType(AclEntryType.DENY)
				.setPrincipal(view.getOwner())
				.setPermissions(AclEntryPermission.READ_DATA)
				.build();
		List<AclEntry> denied = new ArrayList<>();
		denied.add(deny);
		denied.addAll(original);
		try {
			view.setAcl(denied);
			assumeFalse(Files.isReadable(source) && canActuallyRead(source), "请用非特权进程运行：当前进程可绕过读取权限");
			action.run();
		} finally {
			view.setAcl(original);
		}
	}

	/** Files.isReadable 在 Windows 上只看大概；真去打开一次才算数。 */
	private static boolean canActuallyRead(Path source) {
		try {
			if (Files.isDirectory(source)) {
				try (Stream<Path> s = Files.list(source)) {
					s.findAny();
				}
			} else {
				Files.readAllBytes(source);
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void assertSourceFailure(Path source, Instant time) {
		IOException failure = assertThrows(IOException.class,
				() -> VaultBackup.backup(vault(), backups(), 10, at(time)), "不能将漏读报告为成功或未变化");
		assertInstanceOf(AccessDeniedException.class, failure.getCause());
		assertTrue(failure.getMessage().contains(source.toString()), "失败信息应指出无法读取的路径");
	}

	@Test
	void unreadableFileAbortsInsteadOfPublishingPartialZip() throws IOException {
		write("a.md", "虚构笔记 A", OLD);
		Path lock = write(".palace-lock", "虚构锁文件", OLD);
		Path first = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		byte[] original = Files.readAllBytes(first);
		write("a.md", "虚构笔记 B", OLD);
		Files.writeString(vault().resolve("先读到的.md"), "虚构");

		withReadDenied(lock, () -> {
			assertSourceFailure(lock, T0.plusSeconds(60));
			assertEquals(List.of(first.getFileName().toString()), backupNames(), "不能留下残缺 ZIP 或临时文件");
			assertArrayEquals(original, Files.readAllBytes(first));
		});

		Path recovered = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).orElseThrow();
		assertEquals(List.of(".palace-lock", "a.md", "先读到的.md"), zipEntries(recovered));
		assertEquals("虚构锁文件", zipText(recovered, ".palace-lock"));
		assertEquals("虚构笔记 B", zipText(recovered, "a.md"));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(180))).isEmpty());
	}

	@Test
	void unreadableOnlyFileIsFailureNotEmptyVault() throws IOException {
		Path lock = write(".palace-lock", "虚构锁文件", OLD);
		withReadDenied(lock, () -> {
			assertSourceFailure(lock, T0);
			assertEquals(List.of(), backupNames());
		});
		Path recovered = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of(".palace-lock"), zipEntries(recovered));
		assertEquals("虚构锁文件", zipText(recovered, ".palace-lock"));
	}

	@Test
	void unreadableSubdirectoryIsFailureEvenWhenNoFilesWereCollected() throws IOException {
		write("隐藏/.palace-lock", "虚构锁文件", OLD);
		Path hidden = vault().resolve("隐藏");
		withReadDenied(hidden, () -> {
			assertSourceFailure(hidden, T0);
			assertEquals(List.of(), backupNames());
		});
		Path recovered = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of("隐藏/.palace-lock"), zipEntries(recovered));
		assertEquals("虚构锁文件", zipText(recovered, "隐藏/.palace-lock"));
	}

	@Test
	void unreadableNewSubdirectoryIsNotMistakenForUnchangedContents() throws IOException {
		write("a.md", "虚构笔记", OLD);
		Path first = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		write("隐藏/.palace-lock", "虚构锁文件", OLD);
		Path hidden = vault().resolve("隐藏");
		withReadDenied(hidden, () -> {
			assertSourceFailure(hidden, T0.plusSeconds(60));
			assertEquals(List.of(first.getFileName().toString()), backupNames());
		});
		Path recovered = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).orElseThrow();
		assertEquals(List.of("a.md", "隐藏/.palace-lock"), zipEntries(recovered));
	}

	@ParameterizedTest(name = "保留 10 份：原有 {0} 份，目录读取失败 = {1}")
	@CsvSource({"1, false", "10, false", "1, true", "10, true"})
	void tenFailedAttemptsNeverEvictCompleteBackups(int initialCount, boolean denyDirectory) throws IOException {
		Path lock = write("保护/.palace-lock", "虚构锁文件", OLD);
		Map<Path, byte[]> originals = new LinkedHashMap<>();
		for (int i = 0; i < initialCount; i++) {
			write("a.md", "完整版本 " + i, OLD);
			Path zip = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(i))).orElseThrow();
			assertEquals(List.of("a.md", "保护/.palace-lock"), zipEntries(zip));
			assertEquals("虚构锁文件", zipText(zip, "保护/.palace-lock"));
			originals.put(zip, Files.readAllBytes(zip));
		}
		List<String> names = backupNames();
		Path denied = denyDirectory ? lock.getParent() : lock;
		withReadDenied(denied, () -> {
			for (int i = 0; i < 10; i++) {
				write("a.md", "读取失败期间版本 " + i, OLD);
				assertSourceFailure(denied, T0.plusSeconds(60 + i));
				assertEquals(names, backupNames(), "第 " + i + " 次失败不能发布 ZIP、留下临时文件或淘汰完整副本");
				for (var entry : originals.entrySet()) {
					assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
					assertEquals(List.of("a.md", "保护/.palace-lock"), zipEntries(entry.getKey()));
				}
			}
		});
		Path recovered = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).orElseThrow();
		assertEquals(List.of("a.md", "保护/.palace-lock"), zipEntries(recovered));
		assertEquals("读取失败期间版本 9", zipText(recovered, "a.md"));
		assertEquals("虚构锁文件", zipText(recovered, "保护/.palace-lock"));
		assertEquals(Math.min(10, initialCount + 1), backupNames().size(), "仅恢复后成功备份才能触发保留策略");
		assertTrue(Files.exists(recovered));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(180))).isEmpty());
	}

	@Test
	void unreadableRootIsFailureNotEmptyVault() throws IOException {
		write("a.md", "虚构笔记", OLD);
		withReadDenied(vault(), () -> {
			assertSourceFailure(vault(), T0);
			assertEquals(List.of(), backupNames());
		});
	}

	@Test
	void backsUpDeletionOfLastFileAndDeduplicatesEmptySnapshot() throws IOException {
		Path note = write("a.md", "虚构笔记", OLD);
		Path first = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		Files.delete(note);
		Path empty = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(List.of(), zipEntries(empty));
		assertEquals(List.of("a.md"), zipEntries(first));
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).isEmpty());
		assertEquals(List.of(first.getFileName().toString(), empty.getFileName().toString()), backupNames());
	}

	@Test
	void unchangedFilesAreStoredOnlyOnce() throws IOException {
		write("大文件.md", "x".repeat(100_000), OLD);
		write("a.md", "A", OLD);
		VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		assertEquals(2, objectCount());
		for (int i = 1; i <= 5; i++) {
			write("a.md", "A" + i, OLD);
			VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60L * i))).orElseThrow();
		}
		assertEquals(7, objectCount(), "大文件没改过：6 次备份只存了一份");
		// 改名、复制：内容一样，不再多存
		Files.copy(vault().resolve("大文件.md"), vault().resolve("副本.md"));
		Path copied = VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(600))).orElseThrow();
		assertEquals(7, objectCount());
		assertEquals("x".repeat(100_000), zipText(copied, "副本.md"));
	}

	@Test
	void everyKeptBackupStillRestoresItsOwnVersion() throws IOException {
		List<Path> manifests = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			write("a.md", "版本 " + i, OLD);
			manifests.add(VaultBackup.backup(vault(), backups(), 3, at(T0.plusSeconds(i))).orElseThrow());
		}
		assertFalse(Files.exists(manifests.get(0)), "只留最新 3 份");
		for (int i = 1; i < 4; i++) {
			assertEquals("版本 " + i, zipText(manifests.get(i), "a.md"));
		}
		assertEquals(3, objectCount());
	}

	@Test
	void restoreReportsDamagedContentAndNeverOverwrites() throws IOException {
		write("好的.md", "完好", OLD);
		write("坏的.md", "会被弄坏", OLD);
		Path manifest = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		String id = manifest.getFileName().toString().replace(".txt", "");
		// 把"坏的.md"那份内容换成别的（指纹对不上）
		String hash = Files.readAllLines(manifest).stream().filter(l -> l.endsWith(" 坏的.md")).findFirst().orElseThrow().split(" ")[0];
		Path object = backups().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".gz");
		try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(object))) {
			out.write("被改过的内容".getBytes(StandardCharsets.UTF_8));
		}
		Path target = dir.resolve("恢复");
		VaultBackup.Restored result = VaultBackup.restore(backups(), id, target);
		assertEquals(1, result.files());
		assertEquals(List.of("坏的.md"), result.broken());
		assertEquals("完好", Files.readString(target.resolve("好的.md")));
		assertFalse(Files.exists(target.resolve("坏的.md")), "坏掉的不写出来");

		assertThrows(IOException.class, () -> VaultBackup.restore(backups(), id, target), "目标已存在：不覆盖");
		assertThrows(IOException.class, () -> VaultBackup.restore(backups(), "2000-01-01T00-00-00Z", dir.resolve("另一个")));
	}

	@Test
	void restoreRefusesPathsOutsideTheTarget() throws IOException {
		write("a.md", "A", OLD);
		Path manifest = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		String line = Files.readAllLines(manifest).get(1);
		String hash = line.split(" ")[0];
		Files.writeString(manifest, "memory-palace-backup 1\n" + hash + " 1 ../跑出去.md\n" + line + "\n");
		VaultBackup.Restored result = VaultBackup.restore(backups(), manifest.getFileName().toString().replace(".txt", ""), dir.resolve("恢复"));
		assertEquals(List.of("../跑出去.md"), result.broken());
		assertFalse(Files.exists(dir.resolve("跑出去.md")));
		assertEquals(1, result.files());
	}

	@Test
	void damagedManifestBlocksCleanupInsteadOfDeletingContent() throws IOException {
		write("a.md", "A", OLD);
		Path first = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		Files.writeString(first, "看不懂的内容");
		write("a.md", "B", OLD);
		VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		assertEquals(2, objectCount(), "读不出来的清单可能还用着 A：不删");
	}

	@Test
	void latestZipAlwaysMatchesTheNewestBackup() throws IOException {
		write("a.md", "第一版", OLD);
		write("线代/b.md", "B", OLD);
		VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		write("a.md", "第二版", OLD);
		VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(60))).orElseThrow();
		Path zip = backups().resolve(VaultBackup.LATEST_ZIP);
		try (var z = new java.util.zip.ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
			assertEquals(2, z.size());
			try (var in = z.getInputStream(z.getEntry("a.md"))) {
				assertEquals("第二版", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			assertNotNull(z.getEntry("线代/b.md"));
		}
		// ZIP 被删了：内容没变也补上
		Files.delete(zip);
		assertTrue(VaultBackup.backup(vault(), backups(), 10, at(T0.plusSeconds(120))).isEmpty());
		assertTrue(Files.exists(zip));
	}

	@Test
	void restoreIntoVaultMovesCurrentFilesToTrashAndWritesTheBackup() throws IOException {
		write("a.md", "备份时的 A", OLD);
		write("线代/b.md", "B", OLD);
		Path manifest = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		String id = manifest.getFileName().toString().replace(".txt", "");
		write("a.md", "后来改的 A", OLD);
		write("后来新建.md", "新", OLD);
		Files.delete(vault().resolve("线代/b.md"));
		write(".palace/trash/旧回收站/x.md", "回收站里的", OLD);

		VaultBackup.RestoredInPlace result = VaultBackup.restoreInto(backups(), id, vault(), "恢复前");
		assertEquals(2, result.written(), "a.md 和 线代/b.md");
		assertEquals(2, result.movedToTrash(), "后来改的 a.md 和后来新建的");
		assertEquals("备份时的 A", Files.readString(vault().resolve("a.md")));
		assertEquals("B", Files.readString(vault().resolve("线代/b.md")));
		assertFalse(Files.exists(vault().resolve("后来新建.md")));
		Path trash = vault().resolve(".palace/trash/恢复前");
		assertEquals("后来改的 A", Files.readString(trash.resolve("a.md")));
		assertEquals("新", Files.readString(trash.resolve("后来新建.md")));
		assertEquals("回收站里的", Files.readString(vault().resolve(".palace/trash/旧回收站/x.md")), "回收站不动");
	}

	@Test
	void restoreIntoVaultTouchesNothingWhenContentIsDamaged() throws IOException {
		write("a.md", "A", OLD);
		Path manifest = VaultBackup.backup(vault(), backups(), 10, at(T0)).orElseThrow();
		String hash = Files.readAllLines(manifest).get(1).split(" ")[0];
		Files.delete(backups().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".gz"));
		write("a.md", "现在的 A", OLD);
		assertThrows(IOException.class, () -> VaultBackup.restoreInto(backups(), manifest.getFileName().toString().replace(".txt", ""), vault(), "x"));
		assertEquals("现在的 A", Files.readString(vault().resolve("a.md")));
		assertFalse(Files.exists(vault().resolve(".palace/trash")));
	}
}
