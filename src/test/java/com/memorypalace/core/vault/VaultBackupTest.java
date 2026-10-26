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
		assertEquals("2026-10-27_03-04-05.zip", zip.get().getFileName().toString());
		assertEquals(List.of(".palace/locations.tsv", "欢迎.md", "线代/特征值.md"), zipEntries(zip.get()));
		// 没有留下临时文件
		assertEquals(List.of("2026-10-27_03-04-05.zip"), backupNames());
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

	@Test
	void sameSecondGetsSuffix() throws IOException {
		write("a.md", "A", T0.minusSeconds(3600));
		VaultBackup.backup(vault(), backups(), 10, at(T0));
		write("a.md", "A2", Instant.now().plusSeconds(3600));
		Optional<Path> second = VaultBackup.backup(vault(), backups(), 10, at(T0));
		assertEquals("2026-10-27_03-04-05_2.zip", second.orElseThrow().getFileName().toString());
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
				"2026-10-27_03-04-07.zip",
				"2026-10-27_03-04-08.zip",
				"2026-10-27_03-04-09.zip",
				"说明.txt"), backupNames());
	}
}
