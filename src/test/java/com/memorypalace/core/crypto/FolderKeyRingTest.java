package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FolderKeyRingTest {
	private static final UUID NOTE = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final String PLAINTEXT = "# Folder secret\nExisting note content";

	@Test
	void folderIdsAreUrlSafeUniqueAndAbsentFromNormalKeyRings() {
		var first = KeyRing.createFolder(pw("password1"), false);
		var second = KeyRing.createFolder(pw("password1"), false);
		var normal = KeyRing.create(pw("password1"), false);
		try {
			String firstId = KeyRing.folderId(first.content());
			String secondId = KeyRing.folderId(second.content());
			assertNotNull(firstId);
			assertNotNull(secondId);
			assertTrue(firstId.matches("[A-Za-z0-9_-]{22}"), firstId);
			assertTrue(secondId.matches("[A-Za-z0-9_-]{22}"), secondId);
			assertNotEquals(firstId, secondId);
			assertNull(KeyRing.folderId(normal.content()));
		} finally {
			first.key().destroy();
			second.key().destroy();
			normal.key().destroy();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void passwordAndOptionalRecoveryUnlockTheFolderKeyWithoutABox(boolean withRecovery) throws Exception {
		var created = KeyRing.createFolder(pw("password1"), withRecovery);
		try {
			assertFalse(KeyRing.hasBox(created.content()));
			assertNull(KeyRing.boxPublic(created.content()));
			assertEquals(withRecovery, KeyRing.hasRecovery(created.content()));
			MasterKey unlocked = KeyRing.unlock(created.content(), pw("password1"));
			try {
				assertArrayEquals(created.key().bytes(), unlocked.bytes());
			} finally {
				unlocked.destroy();
			}
			assertThrows(WrongKeyException.class, () -> KeyRing.unlock(created.content(), pw("wrong-password")));
			if (withRecovery) {
				assertNotNull(created.recoveryKey());
				MasterKey recovered = KeyRing.unlockWithRecovery(created.content(), created.recoveryKey());
				try {
					assertArrayEquals(created.key().bytes(), recovered.bytes());
				} finally {
					recovered.destroy();
				}
			} else {
				assertNull(created.recoveryKey());
				assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithRecovery(created.content(),
						"AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AA"));
			}
		} finally {
			created.key().destroy();
		}
	}

	@Test
	void changingPasswordPreservesFolderIdKeyRecoveryAndExistingNotes() throws Exception {
		var created = KeyRing.createFolder(pw("password1"), true);
		try {
			String envelope = seal(created);
			String changed = KeyRing.changePassword(created.content(), created.key(), pw("password2"));
			assertThrows(WrongKeyException.class, () -> KeyRing.unlock(changed, pw("password1")));
			assertPasswordOpens(created, changed, "password2", envelope);
			assertTrue(KeyRing.hasRecovery(changed));
			MasterKey recovered = KeyRing.unlockWithRecovery(changed, created.recoveryKey());
			try {
				assertSameFolderAndOpens(created, changed, recovered, envelope);
			} finally {
				recovered.destroy();
			}
		} finally {
			created.key().destroy();
		}
	}

	@Test
	void removingRecoveryPreservesFolderIdKeyAndExistingNotes() throws Exception {
		var created = KeyRing.createFolder(pw("password1"), true);
		try {
			String envelope = seal(created);
			assertThrows(WrongKeyException.class, () -> KeyRing.removeRecovery(created.content(), pw("wrong-password")));
			String removed = KeyRing.removeRecovery(created.content(), pw("password1"));
			assertFalse(KeyRing.hasRecovery(removed));
			assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithRecovery(removed, created.recoveryKey()));
			assertPasswordOpens(created, removed, "password1", envelope);
		} finally {
			created.key().destroy();
		}
	}

	@Test
	void regeneratingRecoveryPreservesFolderIdKeyAndExistingNotes() throws Exception {
		var created = KeyRing.createFolder(pw("password1"), true);
		try {
			String envelope = seal(created);
			assertThrows(WrongKeyException.class, () -> KeyRing.regenerateRecovery(created.content(), pw("wrong-password")));
			var regenerated = KeyRing.regenerateRecovery(created.content(), pw("password1"));
			try {
				assertTrue(KeyRing.hasRecovery(regenerated.content()));
				assertNotNull(regenerated.recoveryKey());
				assertNotEquals(created.recoveryKey(), regenerated.recoveryKey());
				assertThrows(WrongKeyException.class,
						() -> KeyRing.unlockWithRecovery(regenerated.content(), created.recoveryKey()));
				assertSameFolderAndOpens(created, regenerated.content(), regenerated.key(), envelope);
				assertPasswordOpens(created, regenerated.content(), "password1", envelope);
				MasterKey recovered = KeyRing.unlockWithRecovery(regenerated.content(), regenerated.recoveryKey());
				try {
					assertSameFolderAndOpens(created, regenerated.content(), recovered, envelope);
				} finally {
					recovered.destroy();
				}
			} finally {
				regenerated.key().destroy();
			}
		} finally {
			created.key().destroy();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void withBoxLeavesFolderKeyRingContentUnchanged(boolean withRecovery) {
		var created = KeyRing.createFolder(pw("password1"), withRecovery);
		try {
			assertEquals(created.content(), KeyRing.withBox(created.content(), created.key()));
		} finally {
			created.key().destroy();
		}
	}

	@Test
	void malformedFolderIdsAreRejectedDuringParsingAndUnlock() {
		var created = KeyRing.createFolder(pw("password1"), false);
		try {
			for (String id : List.of("", "a".repeat(21), "a".repeat(23),
					"a".repeat(21) + "+", "a".repeat(21) + "/", "a".repeat(21) + "=",
					"a".repeat(21) + ".", "a".repeat(21) + " ", "a".repeat(21) + "中")) {
				String tampered = created.content().lines().map(line -> line.startsWith("folder.id=")
						? "folder.id=" + id : line).collect(Collectors.joining("\n", "", "\n"));
				assertAll("folder.id=" + id,
						() -> assertThrows(IllegalArgumentException.class, () -> KeyRing.folderId(tampered)),
						() -> assertThrows(IllegalArgumentException.class, () -> KeyRing.unlock(tampered, pw("password1"))));
			}
		} finally {
			created.key().destroy();
		}
	}

	@Test
	void validBoxFieldsSplicedIntoAFolderKeyRingAreRejected() {
		var folder = KeyRing.createFolder(pw("password1"), false);
		var normal = KeyRing.create(pw("password1"), false);
		try {
			assertTrue(KeyRing.hasBox(normal.content()));
			String boxFields = normal.content().lines().filter(line -> line.startsWith("box."))
					.collect(Collectors.joining("\n", "", "\n"));
			String tampered = folder.content() + boxFields;
			assertAll(
					() -> assertThrows(IllegalArgumentException.class, () -> KeyRing.folderId(tampered)),
					() -> assertThrows(IllegalArgumentException.class, () -> KeyRing.unlock(tampered, pw("password1"))));
		} finally {
			folder.key().destroy();
			normal.key().destroy();
		}
	}

	@Test
	void folderEnvelopeCannotBeUnlockedWithUserWrappingKeys() throws Exception {
		var created = KeyRing.createFolder(pw("password1"), false);
		byte[] otherBytes = created.key().bytes().clone();
		otherBytes[0] ^= 1;
		MasterKey otherMaster = new MasterKey(otherBytes);
		NoteCipher.wipe(otherBytes);
		byte[] folderWrapping = NoteEnvelope.folderWrappingKey(created.key());
		byte[] sameKeyUserWrapping = NoteEnvelope.userWrappingKey(created.key());
		byte[] otherUserWrapping = NoteEnvelope.userWrappingKey(otherMaster);
		try {
			String envelope = seal(created);
			NoteEnvelope.Lock lock = folderLock(created.content());
			assertEquals(List.of(lock), NoteEnvelope.locks(envelope));
			assertPasswordOpens(created, created.content(), "password1", envelope);
			assertFalse(Arrays.equals(folderWrapping, sameKeyUserWrapping), "Folder and user derivations must be separated");
			// Use the existing FOLDER lock so failure cannot be caused just by an absent USER lock.
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, envelope, lock, otherUserWrapping));
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, envelope, lock, sameKeyUserWrapping));
		} finally {
			NoteCipher.wipe(folderWrapping);
			NoteCipher.wipe(sameKeyUserWrapping);
			NoteCipher.wipe(otherUserWrapping);
			otherMaster.destroy();
			created.key().destroy();
		}
	}

	private static char[] pw(String password) {
		return password.toCharArray();
	}

	private static NoteEnvelope.Lock folderLock(String content) {
		return new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, KeyRing.folderId(content));
	}

	private static String seal(KeyRing.Created created) {
		ContentKey key = ContentKey.random();
		byte[] wrapping = NoteEnvelope.folderWrappingKey(created.key());
		try {
			return NoteEnvelope.seal(NOTE, key, PLAINTEXT, Map.of(folderLock(created.content()), wrapping));
		} finally {
			NoteCipher.wipe(wrapping);
			key.destroy();
		}
	}

	private static void assertPasswordOpens(KeyRing.Created original, String content, String password,
			String envelope) throws WrongKeyException {
		MasterKey unlocked = KeyRing.unlock(content, pw(password));
		try {
			assertSameFolderAndOpens(original, content, unlocked, envelope);
		} finally {
			unlocked.destroy();
		}
	}

	private static void assertSameFolderAndOpens(KeyRing.Created original, String content, MasterKey unlocked,
			String envelope) throws WrongKeyException {
		assertEquals(KeyRing.folderId(original.content()), KeyRing.folderId(content));
		assertArrayEquals(original.key().bytes(), unlocked.bytes());
		assertFalse(KeyRing.hasBox(content));
		assertNull(KeyRing.boxPublic(content));
		byte[] wrapping = NoteEnvelope.folderWrappingKey(unlocked);
		try {
			ContentKey key = NoteEnvelope.unlock(NOTE, envelope, folderLock(original.content()), wrapping);
			try {
				assertEquals(PLAINTEXT, NoteEnvelope.decrypt(NOTE, envelope, key));
			} finally {
				key.destroy();
			}
		} finally {
			NoteCipher.wipe(wrapping);
		}
	}
}
