package com.memorypalace.core.vault;

import com.memorypalace.core.vault.NoteLocations.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteLocationsTest {
	@TempDir
	Path dir;

	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	private static final String OW = "minecraft:overworld";

	private Path file() {
		return dir.resolve(".palace/locations.tsv");
	}

	@Test
	void missingFileIsEmpty() throws IOException {
		var locs = new NoteLocations(file());
		assertEquals(List.of(), locs.get(A));
	}

	@Test
	void recordsMultiplePlacesNewestFirstAndPersists() throws IOException {
		var locs = new NoteLocations(file());
		locs.record(A, new Location(OW, 1, 64, 2, 0, 1000));
		locs.record(A, new Location("minecraft:the_nether", -5, 70, 9, -1, 3000));
		locs.record(A, new Location(OW, 1, 64, 2, 3, 2000));

		var expected = List.of(
				new Location("minecraft:the_nether", -5, 70, 9, -1, 3000),
				new Location(OW, 1, 64, 2, 3, 2000),
				new Location(OW, 1, 64, 2, 0, 1000));
		assertEquals(expected, locs.get(A));
		assertTrue(Files.exists(file()), "父文件夹 .palace/ 要自动创建");

		// 重新读取文件，内容一样
		assertEquals(expected, new NoteLocations(file()).get(A));
	}

	@Test
	void samePlaceUpdatesTimeInsteadOfDuplicating() throws IOException {
		var locs = new NoteLocations(file());
		locs.record(A, new Location(OW, 1, 64, 2, 0, 1000));
		locs.record(A, new Location(OW, 1, 64, 2, 0, 90_000));
		assertEquals(List.of(new Location(OW, 1, 64, 2, 0, 90_000)), locs.get(A));
	}

	@Test
	void clickingAgainWithinAMinuteDoesNotRewrite() throws IOException {
		var locs = new NoteLocations(file());
		locs.record(A, new Location(OW, 1, 64, 2, 0, 1000));
		long size = java.nio.file.Files.getLastModifiedTime(file()).toMillis();
		locs.record(A, new Location(OW, 1, 64, 2, 0, 5000));
		assertEquals(List.of(new Location(OW, 1, 64, 2, 0, 1000)), locs.get(A), "一分钟内又点一下：不更新、不重写");
		assertEquals(size, java.nio.file.Files.getLastModifiedTime(file()).toMillis());
	}

	@Test
	void placeTakenOverByAnotherNote() throws IOException {
		var locs = new NoteLocations(file());
		locs.record(A, new Location(OW, 1, 64, 2, 0, 1000));
		// 同一格后来放了 B 这本书
		locs.record(B, new Location(OW, 1, 64, 2, 0, 2000));
		assertEquals(List.of(), locs.get(A));
		assertEquals(1, locs.get(B).size());
	}

	@Test
	void removeAndPersist() throws IOException {
		var locs = new NoteLocations(file());
		locs.record(A, new Location(OW, 1, 64, 2, 0, 1000));
		locs.record(A, new Location(OW, 9, 64, 9, 1, 2000));
		locs.remove(A, new Location(OW, 1, 64, 2, 0, 99999)); // 时间不同也算同一个地方
		assertEquals(List.of(new Location(OW, 9, 64, 9, 1, 2000)), new NoteLocations(file()).get(A));
	}

	@Test
	void badLinesAreSkipped() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "垃圾\n" + A + "\t" + OW + "\t1\t64\t2\t0\t1000\n" + "not-a-uuid\tx\t1\t2\t3\t4\t5\n",
				StandardCharsets.UTF_8);
		assertEquals(List.of(new Location(OW, 1, 64, 2, 0, 1000)), new NoteLocations(file()).get(A));
	}
}
