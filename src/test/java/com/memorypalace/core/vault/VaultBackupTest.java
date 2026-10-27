package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

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

	private List<String> zipEntries(Path zip) throws IOException {
		try (ZipFile z = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
			List<String> names = new ArrayList<>();
			z.stream().filter(e -> !e.isDirectory()).forEach(e -> names.add(e.getName()));
			names.sort(null);
			return names;
		}
	}

	private List<String> backupNames() throws IOException {
		try (Stream<Path> s = Files.list(backups())) {
			return s.map(p -> p.getFileName().toString()).sorted().toList();
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
		assertEquals("2026-10-27T03-04-05Z.zip", zip.get().getFileName().toString());
		assertEquals(List.of(".palace/locations.tsv", "欢迎.md", "线代/特征值.md"), zipEntries(zip.get()));
		// 没有留下临时文件
		assertEquals(List.of("2026-10-27T03-04-05Z.zip"), backupNames());
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

	private String zipText(Path zip, String entry) throws IOException {
		try (ZipFile z = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
			var e = z.getEntry(entry);
			assertNotNull(e, "ZIP 里应有 " + entry);
			try (var in = z.getInputStream(e)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
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
		assertEquals(List.of("2026-10-27T03-04-05Z.zip", "2026-10-27T03-05-05Z.zip", "2026-10-27T03-06-05Z.zip"), backupNames());
	}

	@Test
	void sameSecondGetsSuffix() throws IOException {
		write("a.md", "A", T0.minusSeconds(3600));
		VaultBackup.backup(vault(), backups(), 10, at(T0));
		write("a.md", "A2", Instant.now().plusSeconds(3600));
		Optional<Path> second = VaultBackup.backup(vault(), backups(), 10, at(T0));
		assertEquals("2026-10-27T03-04-05Z_2.zip", second.orElseThrow().getFileName().toString());
	}

	@Test
	void keepsOnlyNewestAndLeavesOtherFilesAlone() throws IOException {
		Files.createDirectories(backups());
		Files.writeString(backups().resolve("说明.txt"), "别删我");
		for (int i = 0; i < 5; i++) {
			// 每次都让文件"比上一次备份新"
			write("a.md", "v" + i, Instant.now().plusSeconds(3600L * (i + 1)));
			assertTrue(VaultBackup.backup(vault(), backups(), 3, at(T0.plusSeconds(i))).isPresent());
		}
		assertEquals(List.of(
				"2026-10-27T03-04-07Z.zip",
				"2026-10-27T03-04-08Z.zip",
				"2026-10-27T03-04-09Z.zip",
				"说明.txt"), backupNames());
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
		assertEquals(List.of("2026-10-27T03-05-05Z.zip", "2026-10-27T03-06-05Z.zip"), backupNames());
	}
}
