package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FolderLocksTest {
	@TempDir
	Path dir;

	@Test
	void lockedFolderFindsItselfAndTheNearestLockedAncestor() throws IOException {
		var vault = new Vault(dir);
		Files.createDirectories(dir.resolve("locked/nested/deep"));
		writeLockOnDisk("locked", "outer lock");
		assertEquals("locked", FolderLocks.lockedFolder(vault, "locked").orElseThrow());
		assertEquals("locked", FolderLocks.lockedFolder(vault, "locked/nested/deep").orElseThrow());

		// A closer lock added on disk takes precedence without a rescan.
		Path inner = writeLockOnDisk("locked/nested", "inner lock");
		assertEquals("locked/nested", FolderLocks.lockedFolder(vault, "locked/nested").orElseThrow());
		assertEquals("locked/nested", FolderLocks.lockedFolder(vault, "locked/nested/deep").orElseThrow());
		Files.delete(inner);
		assertEquals("locked", FolderLocks.lockedFolder(vault, "locked/nested/deep").orElseThrow());
	}

	@Test
	void unlockedFoldersDoNotInheritSiblingOrRootLocks() throws IOException {
		var vault = new Vault(dir);
		Files.createDirectories(dir.resolve("tree/open/deep"));
		Files.createDirectories(dir.resolve("tree/closed-copy"));
		writeLockOnDisk("tree/closed", "sibling lock");
		assertTrue(FolderLocks.lockedFolder(vault, "tree").isEmpty());
		assertTrue(FolderLocks.lockedFolder(vault, "tree/open/deep").isEmpty());
		assertTrue(FolderLocks.lockedFolder(vault, "tree/closed-copy").isEmpty());
		assertTrue(FolderLocks.lockedFolder(vault, "").isEmpty());

		// Even a manually placed lock at the root must not protect the vault.
		Files.writeString(dir.resolve(FolderLocks.FILE), "root lock", StandardCharsets.UTF_8);
		assertTrue(FolderLocks.lockedFolder(vault, "").isEmpty());
		assertTrue(FolderLocks.lockedFolder(vault, "tree/open/deep").isEmpty());
	}

	@Test
	void lockedFolderOfANoteUsesItsContainingFolder() throws IOException {
		var vault = new Vault(dir);
		NoteSummary note = vault.createNote("private/nested/note.md", "# Note\n");
		writeLockOnDisk("private", "folder lock");
		assertEquals("private", FolderLocks.lockedFolderOf(vault, note).orElseThrow());
	}

	@Test
	void writeCreatesAnAbsentLockAndReadRoundTripsItsContent() throws IOException {
		var vault = new Vault(dir);
		Files.createDirectories(dir.resolve("private"));
		assertTrue(FolderLocks.read(vault, "private").isEmpty());
		String content = "lock contents\r\n秘密 🔐\r\n";
		assertEquals(FolderLocks.WriteResult.OK, FolderLocks.write(vault, "private", "", content));
		assertTrue(Files.isRegularFile(dir.resolve("private/" + FolderLocks.FILE)));
		assertEquals(content, FolderLocks.read(vault, "private").orElseThrow());
		assertEquals(content, Files.readString(dir.resolve("private/" + FolderLocks.FILE), StandardCharsets.UTF_8));
	}

	@Test
	void emptyExpectedHashCannotOverwriteAnExistingLock() throws IOException {
		var vault = new Vault(dir);
		Path file = writeLockOnDisk("private", "existing lock\r\n");
		byte[] original = Files.readAllBytes(file);
		assertEquals(FolderLocks.WriteResult.CONFLICT, FolderLocks.write(vault, "private", "", "replacement"));
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	void staleHashCannotOverwriteALockChangedOnDisk() throws IOException {
		var vault = new Vault(dir);
		Path file = writeLockOnDisk("private", "original lock\n");
		String openedHash = Vault.hash(FolderLocks.read(vault, "private").orElseThrow());
		Files.writeString(file, "externally replaced lock\r\n", StandardCharsets.UTF_8);
		byte[] current = Files.readAllBytes(file);

		assertEquals(FolderLocks.WriteResult.CONFLICT, FolderLocks.write(vault, "private", openedHash, "stale replacement"));
		assertArrayEquals(current, Files.readAllBytes(file));
		assertEquals("externally replaced lock\r\n", FolderLocks.read(vault, "private").orElseThrow());
	}

	@Test
	void matchingHashAllowsReplacingTheCurrentLock() throws IOException {
		var vault = new Vault(dir);
		String current = "current lock\n";
		Path file = writeLockOnDisk("private", current);
		String replacement = "updated lock\n";
		assertEquals(FolderLocks.WriteResult.OK,
				FolderLocks.write(vault, "private", Vault.hash(current), replacement));
		assertEquals(replacement, FolderLocks.read(vault, "private").orElseThrow());
		assertEquals(replacement, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void missingFolderIsNotCreatedByWritingALock() throws IOException {
		var vault = new Vault(dir);
		assertEquals(FolderLocks.WriteResult.MISSING_FOLDER,
				FolderLocks.write(vault, "missing/nested", "", "lock contents"));
		assertFalse(Files.exists(dir.resolve("missing")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/a", "a/", "a//b", "../x", "a/../b", ".palace", "a/.hidden", "C:/x", "a\\b"})
	void invalidFolderPathsAreRejectedByEveryOperation(String folder) throws IOException {
		var vault = new Vault(dir);
		assertAll(folder,
				() -> assertThrows(IllegalArgumentException.class, () -> FolderLocks.write(vault, folder, "", "lock")),
				() -> assertThrows(IllegalArgumentException.class, () -> FolderLocks.read(vault, folder)),
				() -> assertThrows(IllegalArgumentException.class, () -> FolderLocks.lockedFolder(vault, folder)));
	}

	@Test
	void rootCannotBeReadOrWrittenAsALockedFolder() throws IOException {
		var vault = new Vault(dir);
		// Root is a valid lookup returning empty, but is not a valid lock-file location.
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> FolderLocks.write(vault, "", "", "lock")),
				() -> assertThrows(IllegalArgumentException.class, () -> FolderLocks.read(vault, "")));
		assertFalse(Files.exists(dir.resolve(FolderLocks.FILE)));
	}

	@Test
	void scanDoesNotCreateANoteFromOrRewriteALockFile() throws IOException {
		var vault = new Vault(dir);
		UUID lockId = UUID.randomUUID();
		Path file = writeLockOnDisk("private", "---\npalace-id: " + lockId + "\n---\n# Not a note\n");
		byte[] original = Files.readAllBytes(file);
		Files.writeString(dir.resolve("private/note.md"), "# Real note\n", StandardCharsets.UTF_8);
		vault.scan();

		assertEquals(List.of("private/note.md"), vault.list().stream().map(NoteSummary::relativePath).toList());
		assertTrue(vault.find(lockId).isEmpty());
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	private Path writeLockOnDisk(String folder, String content) throws IOException {
		Path file = dir.resolve(folder).resolve(FolderLocks.FILE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}
}
