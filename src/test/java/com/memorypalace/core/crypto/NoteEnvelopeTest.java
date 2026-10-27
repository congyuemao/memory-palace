package com.memorypalace.core.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NoteEnvelopeTest {
	private static final UUID NOTE = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
	private static final NoteEnvelope.Lock USER = NoteEnvelope.Lock.user("host");
	private static final NoteEnvelope.Lock FOLDER = new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, "host");
	private final ContentKey key = new ContentKey(keyBytes(1));
	private final byte[] wrappingKey = keyBytes(2);

	@AfterEach
	void destroyKey() {
		key.destroy();
		NoteCipher.wipe(wrappingKey);
	}

	@ParameterizedTest(name = "USER lock round trip: {0}")
	@MethodSource("plaintexts")
	void userLockRoundTripsExactPlaintext(String description, String plaintext) throws Exception {
		String envelope = seal(plaintext);
		assertTrue(envelope.startsWith("MPAL2:"));
		assertEquals(List.of(USER), NoteEnvelope.locks(envelope));
		assertUnlocks(NOTE, envelope, USER, wrappingKey, plaintext);
		assertUnlocks(NOTE, " \r\n" + envelope + "\n\t", USER, wrappingKey, plaintext);
	}

	static Stream<Arguments> plaintexts() {
		return Stream.of(
				Arguments.of("Chinese and emoji", "# 日记\n今天学了特征值 😀🔐"),
				Arguments.of("empty", ""),
				Arguments.of("whitespace and NUL", " \t\r\n\u0000正文\n "),
				Arguments.of("200 KiB", "0123456789abcdef".repeat(12_800)));
	}

	@Test
	void everyLockIndependentlyUnlocksAndInsertionOrderIsPreserved() throws Exception {
		MasterKey master = new MasterKey(keyBytes(3));
		MasterKey folderMaster = new MasterKey(keyBytes(4));
		NoteEnvelope.Lock passwordLock = passwordLock("a2id,8,1,1," + base64(new byte[16]) + CHECK);
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		try {
			locks.put(USER, NoteEnvelope.userWrappingKey(master));
			locks.put(passwordLock, NoteEnvelope.passwordWrappingKey(passwordLock, "reading-password".toCharArray()));
			locks.put(FOLDER, NoteEnvelope.folderWrappingKey(folderMaster));
			String envelope = NoteEnvelope.seal(NOTE, key, "三把锁 🔐", locks);
			assertEquals(List.of(USER, passwordLock, FOLDER), NoteEnvelope.locks(envelope));
			for (var entry : locks.entrySet()) {
				assertUnlocks(NOTE, envelope, entry.getKey(), entry.getValue(), "三把锁 🔐");
			}
		} finally {
			locks.values().forEach(NoteCipher::wipe);
			master.destroy();
			folderMaster.destroy();
		}
	}

	@Test
	void isEnvelopeRecognizesOnlyTheMpal2PrefixIgnoringSurroundingWhitespace() {
		assertTrue(NoteEnvelope.isEnvelope("MPAL2:..."));
		assertTrue(NoteEnvelope.isEnvelope(" \t\r\nMPAL2:...\n "));
		assertFalse(NoteEnvelope.isEnvelope("MPAL1:..."));
		assertFalse(NoteEnvelope.isEnvelope("plain text"));
		assertFalse(NoteEnvelope.isEnvelope("text MPAL2:..."));
		assertFalse(NoteEnvelope.isEnvelope(""));
		assertFalse(NoteEnvelope.isEnvelope(null));
	}

	@Test
	void withBodyKeepsEveryWrapAndUsesAFreshNonceEvenForIdenticalPlaintext() throws Exception {
		byte[] folderKey = keyBytes(3);
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		locks.put(USER, wrappingKey);
		locks.put(FOLDER, folderKey);
		String original = NoteEnvelope.seal(NOTE, key, "old body", locks);
		String changed = NoteEnvelope.withBody(NOTE, original, key, "新正文 😀");
		String repeated = NoteEnvelope.withBody(NOTE, changed, key, "新正文 😀");

		assertEquals(header(original), header(changed));
		assertEquals(header(original), header(repeated));
		assertEquals(List.of(USER, FOLDER), NoteEnvelope.locks(changed));
		assertNotEquals(payload(original), payload(changed));
		assertNotEquals(payload(changed), payload(repeated));
		assertFalse(Arrays.equals(nonce(payload(original)), nonce(payload(changed))));
		assertFalse(Arrays.equals(nonce(payload(changed)), nonce(payload(repeated))));
		for (String envelope : List.of(changed, repeated)) {
			assertUnlocks(NOTE, envelope, USER, wrappingKey, "新正文 😀");
			assertUnlocks(NOTE, envelope, FOLDER, folderKey, "新正文 😀");
		}
		assertUnlocks(NOTE, original, USER, wrappingKey, "old body");
	}

	@Test
	void sealingIdenticalPlaintextUsesFreshBodyAndWrapNonces() {
		String first = seal("same text");
		String second = seal("same text");
		assertNotEquals(payload(first), payload(second));
		assertNotEquals(wrap(first, 0), wrap(second, 0));
		assertFalse(Arrays.equals(nonce(payload(first)), nonce(payload(second))));
		assertFalse(Arrays.equals(nonce(wrap(first, 0)), nonce(wrap(second, 0))));
	}

	@Test
	void locksCanBeAddedReplacedAndRemovedWithoutChangingTheBody() throws Exception {
		String original = seal("body stays intact");
		byte[] folderKey = keyBytes(3);
		byte[] replacementKey = keyBytes(4);
		// The same id with a different kind is a distinct lock.
		String added = NoteEnvelope.withLock(NOTE, original, key, FOLDER, folderKey);
		assertEquals(List.of(USER, FOLDER), NoteEnvelope.locks(added));
		assertEquals(payload(original), payload(added));
		assertEquals(wrap(original, 0), wrap(added, 0));
		assertUnlocks(NOTE, added, USER, wrappingKey, "body stays intact");
		assertUnlocks(NOTE, added, FOLDER, folderKey, "body stays intact");

		NoteEnvelope.Lock sameUser = new NoteEnvelope.Lock(NoteEnvelope.Kind.USER, "host");
		String replaced = NoteEnvelope.withLock(NOTE, added, key, sameUser, replacementKey);
		assertEquals(List.of(USER, FOLDER), NoteEnvelope.locks(replaced));
		assertEquals(payload(original), payload(replaced));
		assertEquals(wrap(added, 1), wrap(replaced, 1));
		assertNotEquals(wrap(added, 0), wrap(replaced, 0));
		assertUnlocks(NOTE, replaced, USER, replacementKey, "body stays intact");
		assertUnlocks(NOTE, replaced, FOLDER, folderKey, "body stays intact");
		assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, replaced, USER, wrappingKey));

		String removed = NoteEnvelope.withoutLock(replaced, FOLDER);
		assertEquals(List.of(USER), NoteEnvelope.locks(removed));
		assertEquals(payload(original), payload(removed));
		assertUnlocks(NOTE, removed, USER, replacementKey, "body stays intact");
		assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, removed, FOLDER, folderKey));
		assertEquals(removed, NoteEnvelope.withoutLock(removed, FOLDER));
		assertThrows(IllegalStateException.class, () -> NoteEnvelope.withoutLock(removed, USER));
		assertUnlocks(NOTE, original, USER, wrappingKey, "body stays intact");
	}

	@Test
	void maximumLockCountAllowsReplacementButRejectsAnAdditionalLock() throws Exception {
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		for (int i = 0; i < NoteEnvelope.MAX_LOCKS; i++) {
			locks.put(NoteEnvelope.Lock.user("owner-" + i), wrappingKey);
		}
		String full = NoteEnvelope.seal(NOTE, key, "full", locks);
		assertEquals(List.copyOf(locks.keySet()), NoteEnvelope.locks(full));
		NoteEnvelope.Lock extra = NoteEnvelope.Lock.user("extra");
		assertThrows(IllegalArgumentException.class,
				() -> NoteEnvelope.withLock(NOTE, full, key, extra, wrappingKey));
		NoteEnvelope.Lock first = NoteEnvelope.Lock.user("owner-0");
		String replaced = NoteEnvelope.withLock(NOTE, full, key, first, keyBytes(3));
		assertEquals(NoteEnvelope.MAX_LOCKS, NoteEnvelope.locks(replaced).size());
		assertUnlocks(NOTE, replaced, first, keyBytes(3), "full");
		locks.put(extra, wrappingKey);
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.seal(NOTE, key, "full", locks));
	}

	@Test
	void userWrappingKeyIsDeterministicAndSeparatedFromTheMasterAndFolderKeys() {
		MasterKey master = new MasterKey(keyBytes(3));
		MasterKey sameMaster = new MasterKey(keyBytes(3));
		MasterKey otherMaster = new MasterKey(keyBytes(4));
		byte[] first = null;
		byte[] again = null;
		byte[] same = null;
		byte[] other = null;
		byte[] folder = null;
		try {
			first = NoteEnvelope.userWrappingKey(master);
			again = NoteEnvelope.userWrappingKey(master);
			same = NoteEnvelope.userWrappingKey(sameMaster);
			other = NoteEnvelope.userWrappingKey(otherMaster);
			folder = NoteEnvelope.folderWrappingKey(master);
			assertEquals(32, first.length);
			assertArrayEquals(first, again);
			assertArrayEquals(first, same);
			assertFalse(Arrays.equals(first, other));
			assertFalse(Arrays.equals(first, master.bytes()));
			assertFalse(Arrays.equals(first, folder));
			assertArrayEquals(keyBytes(3), master.bytes());
		} finally {
			NoteCipher.wipe(first);
			NoteCipher.wipe(again);
			NoteCipher.wipe(same);
			NoteCipher.wipe(other);
			NoteCipher.wipe(folder);
			master.destroy();
			sameMaster.destroy();
			otherMaster.destroy();
		}
	}

	@Test
	void newPasswordLocksUseFreshSaltsAndOnlyTheMatchingPasswordUnlocks() throws Exception {
		char[] password = "阅读密码 😀".toCharArray();
		var first = NoteEnvelope.newPasswordLock(password);
		var second = NoteEnvelope.newPasswordLock(password);
		byte[] derived = null;
		byte[] wrong = null;
		try {
			assertEquals(NoteEnvelope.Kind.PASSWORD, first.lock().kind());
			assertEquals(NoteEnvelope.Kind.PASSWORD, second.lock().kind());
			String[] firstFields = first.lock().id().split(",", -1);
			String[] secondFields = second.lock().id().split(",", -1);
			assertEquals(6, firstFields.length);
			assertEquals(6, secondFields.length);
			assertEquals(32, Base64.getDecoder().decode(firstFields[5]).length);
			assertArrayEquals(new String[]{"a2id", Integer.toString(PasswordKdf.MEMORY_KIB),
					Integer.toString(PasswordKdf.ITERATIONS), Integer.toString(PasswordKdf.PARALLELISM)},
					Arrays.copyOf(firstFields, 4));
			assertEquals(16, Base64.getDecoder().decode(firstFields[4]).length);
			assertEquals(16, Base64.getDecoder().decode(secondFields[4]).length);
			assertNotEquals(first.lock().id(), second.lock().id());
			assertNotEquals(firstFields[4], secondFields[4]);
			assertFalse(Arrays.equals(first.wrappingKey(), second.wrappingKey()));
			derived = NoteEnvelope.passwordWrappingKey(first.lock(), password);
			assertEquals(32, derived.length);
			assertArrayEquals(first.wrappingKey(), derived);
			String envelope = NoteEnvelope.seal(NOTE, key, "password protected", Map.of(first.lock(), first.wrappingKey()));
			assertUnlocks(NOTE, envelope, first.lock(), derived, "password protected");
			wrong = NoteEnvelope.passwordWrappingKey(first.lock(), "wrong password".toCharArray());
			byte[] wrongKey = wrong;
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, envelope, first.lock(), wrongKey));
		} finally {
			Arrays.fill(password, '\0');
			NoteCipher.wipe(first.wrappingKey());
			NoteCipher.wipe(second.wrappingKey());
			NoteCipher.wipe(derived);
			NoteCipher.wipe(wrong);
		}
	}

	@Test
	void readerDerivesTheSameKeysFromTheChallengeAndOnlyTheRightVerifierPasses() throws Exception {
		char[] password = "一句话当阅读密码".toCharArray();
		var created = NoteEnvelope.newPasswordLock(password);
		NoteEnvelope.PasswordKeys right = null;
		NoteEnvelope.PasswordKeys wrong = null;
		try {
			String challenge = NoteEnvelope.passwordChallenge(created.lock());
			// 题目不含最后一段（验证码的哈希），发出去也没法离线猜密码
			assertEquals(5, challenge.split(",", -1).length);
			assertTrue(created.lock().id().startsWith(challenge + ","));
			right = NoteEnvelope.passwordKeys(challenge, password);
			assertArrayEquals(created.wrappingKey(), right.wrappingKey());
			assertEquals(32, right.verifier().length);
			assertFalse(Arrays.equals(right.wrappingKey(), right.verifier()));
			assertTrue(NoteEnvelope.checkPassword(created.lock(), right.verifier()));

			wrong = NoteEnvelope.passwordKeys(challenge, "猜错的密码".toCharArray());
			assertFalse(NoteEnvelope.checkPassword(created.lock(), wrong.verifier()));
			assertFalse(NoteEnvelope.checkPassword(created.lock(), null));
			assertFalse(NoteEnvelope.checkPassword(created.lock(), new byte[31]));
			assertFalse(NoteEnvelope.checkPassword(USER, right.verifier()));
			assertFalse(NoteEnvelope.checkPassword(passwordLock(challenge + ",not-base64"), right.verifier()));
			// 换了盐（重新设密码）之后，旧的验证码不再通过
			var again = NoteEnvelope.newPasswordLock(password);
			try {
				assertFalse(NoteEnvelope.checkPassword(again.lock(), right.verifier()));
			} finally {
				NoteCipher.wipe(again.wrappingKey());
			}
		} finally {
			Arrays.fill(password, '\0');
			NoteCipher.wipe(created.wrappingKey());
			if (right != null) {
				right.wipe();
			}
			if (wrong != null) {
				wrong.wipe();
			}
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"a2id,8,1,1", "argon2i,8,1,1,AAAAAAAAAAAAAAAAAAAAAA==", "a2id,999999,1,1,AAAAAAAAAAAAAAAAAAAAAA==",
			"a2id,8,1,1,AAAAAAAAAAAAAAAAAAAAAA==,extra"})
	void passwordKeysRejectsMalformedChallenges(String challenge) {
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.passwordKeys(challenge, "password".toCharArray()));
	}

	@Test
	void onlyChallengesAtLeastAsStrongAsTheDefaultsAreAccepted() {
		String salt = base64(new byte[16]);
		var created = NoteEnvelope.newPasswordLock("default-strength".toCharArray());
		try {
			assertTrue(NoteEnvelope.challengeIsStrong(NoteEnvelope.passwordChallenge(created.lock())));
		} finally {
			NoteCipher.wipe(created.wrappingKey());
		}
		assertTrue(NoteEnvelope.challengeIsStrong("a2id,131072,3,2," + salt));
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,8,1,1," + salt));
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,65536,2,1," + salt));
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,32768,3,1," + salt));
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,262144,10,1," + salt)); // 超出允许范围
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,65536,3,1," + base64(new byte[15])));
		assertFalse(NoteEnvelope.challengeIsStrong("a2id,65536,3,1"));
		assertFalse(NoteEnvelope.challengeIsStrong(null));
	}

	@Test
	void passwordChallengeRejectsNonPasswordAndFiveFieldLocks() {
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.passwordChallenge(USER));
		assertThrows(IllegalArgumentException.class,
				() -> NoteEnvelope.passwordChallenge(passwordLock("a2id,8,1,1," + base64(new byte[16]))));
	}

	@Test
	void passwordLockLookupFindsTheOnlyPasswordLock() {
		NoteEnvelope.Lock password = passwordLock("a2id,8,1,1," + base64(new byte[16]) + CHECK);
		String userOnly = NoteEnvelope.seal(NOTE, key, "正文", Map.of(USER, wrappingKey));
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		locks.put(USER, wrappingKey);
		locks.put(password, wrappingKey);
		String both = NoteEnvelope.seal(NOTE, key, "正文", locks);
		assertTrue(NoteEnvelope.passwordLock(userOnly).isEmpty());
		assertEquals(0, NoteEnvelope.passwordLockCount(userOnly));
		assertEquals(password, NoteEnvelope.passwordLock(both).orElseThrow());
		assertEquals(1, NoteEnvelope.passwordLockCount(both));
		locks.put(passwordLock("a2id,8,1,1," + base64(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}) + CHECK), wrappingKey);
		assertEquals(2, NoteEnvelope.passwordLockCount(NoteEnvelope.seal(NOTE, key, "正文", locks)));
	}

	@Test
	void unlockAsUserPrefersTheGivenIdButFallsBackToOtherUserLocks() throws Exception {
		MasterKey master = new MasterKey(keyBytes(7));
		MasterKey stranger = new MasterKey(keyBytes(8));
		byte[] mine = NoteEnvelope.userWrappingKey(master);
		byte[] theirs = NoteEnvelope.userWrappingKey(stranger);
		try {
			Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
			locks.put(NoteEnvelope.Lock.user("someone-else"), theirs);
			locks.put(NoteEnvelope.Lock.user("old-account"), mine);
			locks.put(FOLDER, wrappingKey);
			String envelope = NoteEnvelope.seal(NOTE, key, "换了账号也能打开", locks);
			for (String preferred : new String[]{"old-account", "new-account", "someone-else"}) {
				ContentKey opened = NoteEnvelope.unlockAsUser(NOTE, envelope, master, preferred);
				try {
					assertEquals("换了账号也能打开", NoteEnvelope.decrypt(NOTE, envelope, opened));
				} finally {
					opened.destroy();
				}
			}
			MasterKey wrong = new MasterKey(keyBytes(9));
			try {
				assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlockAsUser(NOTE, envelope, wrong, "old-account"));
			} finally {
				wrong.destroy();
			}
			String folderOnly = NoteEnvelope.seal(NOTE, key, "x", Map.of(FOLDER, wrappingKey));
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlockAsUser(NOTE, folderOnly, master, "old-account"));
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlockAsUser(NOTE, "MPAL2:broken", master, "old-account"));
		} finally {
			NoteCipher.wipe(mine);
			NoteCipher.wipe(theirs);
			master.destroy();
			stranger.destroy();
		}
	}

	@Test
	void wrongWrappingKeyOrAbsentLockCannotUnlock() {
		String envelope = seal("secret");
		assertAll(
				() -> assertThrows(WrongKeyException.class,
						() -> NoteEnvelope.unlock(NOTE, envelope, USER, keyBytes(3))),
				() -> assertThrows(WrongKeyException.class,
						() -> NoteEnvelope.unlock(NOTE, envelope, NoteEnvelope.Lock.user("absent"), wrappingKey)));
	}

	@Test
	void copyingAnEnvelopeToAnotherNoteBreaksBothWrapAndBodyAuthentication() {
		String envelope = seal("bound to note id");
		assertAll(
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(OTHER, envelope, USER, wrappingKey)),
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(OTHER, envelope, key)));
	}

	@ParameterizedTest
	@CsvSource({"FOLDER, host", "USER, other-owner"})
	void changingLockKindOrIdCannotReuseItsWrap(NoteEnvelope.Kind kind, String id) {
		String original = seal("secret");
		NoteEnvelope.Lock changedLock = new NoteEnvelope.Lock(kind, id);
		String changed = text(kind.code + "." + id + "." + wrap(original, 0), payload(original));
		assertEquals(List.of(changedLock), NoteEnvelope.locks(changed));
		// Request the edited lock: failure must come from AAD, not from a missing map entry.
		assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, changed, changedLock, wrappingKey));
	}

	@Test
	void movingWrapsBetweenExistingLocksFailsEvenWhenTheirWrappingKeysAreIdentical() {
		NoteEnvelope.Lock otherUser = NoteEnvelope.Lock.user("guest");
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		locks.put(USER, wrappingKey);
		locks.put(otherUser, wrappingKey);
		String original = NoteEnvelope.seal(NOTE, key, "secret", locks);
		String swapped = text("u.host." + wrap(original, 1) + ";u.guest." + wrap(original, 0), payload(original));
		assertEquals(List.of(USER, otherUser), NoteEnvelope.locks(swapped));
		assertAll(
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, swapped, USER, wrappingKey)),
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, swapped, otherUser, wrappingKey)));
	}

	@ParameterizedTest(name = "tamper {0} Base64 character {1}")
	@CsvSource({"body, 0", "body, 16", "body, 60", "wrap, 0", "wrap, 16", "wrap, 76"})
	void changingOneCharacterInNonceCiphertextOrTagFailsAuthentication(String target, int index) {
		String original = seal("authenticated message");
		if (target.equals("body")) {
			String changed = header(original) + "|" + flipCharacter(payload(original), index);
			assertEquals(List.of(USER), NoteEnvelope.locks(changed));
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(NOTE, changed, key));
		} else {
			String changed = text("u.host." + flipCharacter(wrap(original, 0), index), payload(original));
			assertEquals(List.of(USER), NoteEnvelope.locks(changed));
			assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, changed, USER, wrappingKey));
		}
	}

	@Test
	void splicedPayloadFailsWithBothTheDestinationKeyAndTheSourceKey() throws Exception {
		ContentKey otherKey = new ContentKey(keyBytes(3));
		try {
			String source = seal("source note");
			String destination = NoteEnvelope.seal(OTHER, otherKey, "destination note", Map.of(USER, wrappingKey));
			String spliced = header(destination) + "|" + payload(source);
			ContentKey unlocked = NoteEnvelope.unlock(OTHER, spliced, USER, wrappingKey);
			try {
				assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(OTHER, spliced, unlocked));
				// Even the key that encrypted this payload cannot bypass the note-id binding.
				assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(OTHER, spliced, key));
			} finally {
				unlocked.destroy();
			}
		} finally {
			otherKey.destroy();
		}
	}

	@Test
	void anotherNotesContentKeyCannotDecryptOrRewriteTheEnvelope() throws Exception {
		ContentKey otherKey = new ContentKey(keyBytes(3));
		try {
			String envelope = seal("original body");
			String otherEnvelope = NoteEnvelope.seal(OTHER, otherKey, "other body", Map.of(USER, wrappingKey));
			ContentKey unlocked = NoteEnvelope.unlock(OTHER, otherEnvelope, USER, wrappingKey);
			try {
				assertAll(
						() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(NOTE, envelope, unlocked)),
						() -> assertThrows(WrongKeyException.class,
								() -> NoteEnvelope.withBody(NOTE, envelope, unlocked, "attacker body")),
						() -> assertThrows(WrongKeyException.class,
								() -> NoteEnvelope.withLock(NOTE, envelope, unlocked, FOLDER, wrappingKey)));
				assertEquals(List.of(USER), NoteEnvelope.locks(envelope));
				assertUnlocks(NOTE, envelope, USER, wrappingKey, "original body");
			} finally {
				unlocked.destroy();
			}
		} finally {
			otherKey.destroy();
		}
	}

	@Test
	void damagedBodyCannotBeReplacedOrGivenAnotherLockEvenWithTheOriginalKey() {
		String original = seal("original body");
		String damaged = header(original) + "|" + flipCharacter(payload(original), 16);
		assertAll(
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.withBody(NOTE, damaged, key, "replacement")),
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.withLock(NOTE, damaged, key, FOLDER, wrappingKey)));
	}

	@ParameterizedTest(name = "malformed envelope: {0}")
	@MethodSource("malformedEnvelopes")
	void malformedTextIsRejectedByEveryEntryPoint(String description, String envelope) {
		assertAll(description,
				() -> assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.locks(envelope)),
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.unlock(NOTE, envelope, USER, wrappingKey)),
				() -> assertThrows(WrongKeyException.class, () -> NoteEnvelope.decrypt(NOTE, envelope, key)));
	}

	static Stream<Arguments> malformedEnvelopes() {
		// Start from authentic ciphertext so accepting a noncanonical spelling cannot hide behind a bad tag.
		ContentKey key = new ContentKey(keyBytes(1));
		String valid;
		try {
			valid = NoteEnvelope.seal(NOTE, key, "", Map.of(USER, keyBytes(2)));
		} finally {
			key.destroy();
		}
		String wrap = wrap(valid, 0);
		String body = payload(valid); // An empty plaintext produces 28 bytes and two padding characters.
		String entry = "u.host." + wrap;
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("null", null);
		cases.put("empty", "");
		cases.put("plain text", "plain text");
		cases.put("old format", "MPAL1:" + body);
		cases.put("missing separator", "MPAL2:" + entry + body);
		cases.put("two separators", valid + "|");
		cases.put("empty lock list", text("", body));
		cases.put("empty payload", text(entry, ""));
		cases.put("unknown kind", text("x.host." + wrap, body));
		cases.put("uppercase kind", text("U.host." + wrap, body));
		cases.put("missing kind", text(".host." + wrap, body));
		cases.put("duplicate lock", text(entry + ";" + entry, body));
		cases.put("duplicate lock with different wrap", text(entry + ";u.host." + flipCharacter(wrap, 0), body));
		cases.put("two parts", text("u." + wrap, body));
		cases.put("four parts", text("u.host.extra." + wrap, body));
		cases.put("leading empty lock", text(";" + entry, body));
		cases.put("trailing empty lock", text(entry + ";", body));
		cases.put("empty id", text("u.." + wrap, body));
		for (String id : List.of("bad.id", "bad;id", "bad|id", "bad id", "bad\tid", "bad\nid", "用户", "a".repeat(129))) {
			cases.put("illegal id: " + id, text("u." + id + "." + wrap, body));
		}
		cases.put("missing Base64 padding", text(entry, body.replace("=", "")));
		cases.put("extra Base64 padding", text(entry, body + "="));
		cases.put("characters after Base64 padding", text(entry, body + "A"));
		cases.put("nonzero Base64 padding bits", text(entry, nonzeroPaddingBits(body)));
		cases.put("payload internal whitespace", text(entry, body.substring(0, 8) + "\n" + body.substring(8)));
		cases.put("invalid payload alphabet", text(entry, "!" + body.substring(1)));
		cases.put("extra wrap padding", text(entry + "=", body));
		cases.put("wrap internal whitespace", text("u.host." + wrap.substring(0, 8) + " " + wrap.substring(8), body));
		cases.put("invalid wrap alphabet", text("u.host._" + wrap.substring(1), body));
		for (int length : new int[]{0, 28, 59, 61}) {
			cases.put("wrap length " + length, text("u.host." + base64(new byte[length]), body));
		}
		for (int length : new int[]{1, 12, 27}) {
			cases.put("payload length " + length, text(entry, base64(new byte[length])));
		}
		String tooMany = IntStream.rangeClosed(0, NoteEnvelope.MAX_LOCKS)
				.mapToObj(i -> "u.owner-" + i + "." + wrap).collect(Collectors.joining(";"));
		cases.put("too many locks", text(tooMany, body));
		return cases.entrySet().stream().map(entryCase -> Arguments.of(entryCase.getKey(), entryCase.getValue()));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"bad.id", "bad;id", "bad|id", "bad id", "bad\tid", "bad\nid", "用户", "😀"})
	void lockConstructorRejectsNullEmptyOrIllegalIds(String id) {
		assertThrows(IllegalArgumentException.class, () -> new NoteEnvelope.Lock(NoteEnvelope.Kind.USER, id));
	}

	@Test
	void lockConstructorRejectsNullKindAndEnforcesTheIdLengthBoundary() {
		assertThrows(NullPointerException.class, () -> new NoteEnvelope.Lock(null, "host"));
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.Lock.user("a".repeat(129)));
		assertEquals("a".repeat(128), NoteEnvelope.Lock.user("a".repeat(128)).id());
	}

	@ParameterizedTest(name = "invalid password lock: {0}")
	@MethodSource("invalidPasswordLocks")
	void passwordWrappingKeyRejectsInvalidMetadata(String description, NoteEnvelope.Lock lock) {
		// Invalid resource parameters must be rejected before Argon2 is attempted.
		assertThrows(IllegalArgumentException.class,
				() -> NoteEnvelope.passwordWrappingKey(lock, "valid-password".toCharArray()));
	}

	static Stream<Arguments> invalidPasswordLocks() {
		String salt = base64(new byte[16]);
		Map<String, NoteEnvelope.Lock> cases = new LinkedHashMap<>();
		cases.put("USER lock", USER);
		cases.put("FOLDER lock", FOLDER);
		Map<String, String> ids = new LinkedHashMap<>();
		ids.put("too few fields", "a2id,8,1," + salt);
		ids.put("too many fields", "a2id,8,1,1," + salt + ",extra");
		ids.put("unknown algorithm", "argon2i,8,1,1," + salt);
		ids.put("memory too large", "a2id,999999,1,1," + salt);
		ids.put("memory too small", "a2id,7,1,1," + salt);
		ids.put("zero iterations", "a2id,8,0,1," + salt);
		ids.put("iterations too large", "a2id,8,11,1," + salt);
		ids.put("parallelism too large", "a2id,72,1,9," + salt);
		ids.put("memory times iterations too large", "a2id,262144,4,1," + salt);
		ids.put("nonnumeric parameter", "a2id,no,1,1," + salt);
		ids.put("negative parameter", "a2id,-8,1,1," + salt);
		ids.put("leading zero parameter", "a2id,08,1,1," + salt);
		ids.put("integer overflow", "a2id,2147483648,1,1," + salt);
		ids.put("empty salt", "a2id,8,1,1,");
		ids.put("short salt", "a2id,8,1,1," + base64(new byte[15]));
		ids.put("long salt", "a2id,8,1,1," + base64(new byte[17]));
		ids.put("unpadded salt", "a2id,8,1,1," + salt.replace("=", ""));
		ids.put("noncanonical salt padding bits", "a2id,8,1,1," + nonzeroPaddingBits(salt));
		ids.forEach((description, id) -> cases.put(description, passwordLock(id + CHECK)));
		return cases.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
	}

	@Test
	void sealRejectsAnEmptyLockMapOrANullWrappingKey() {
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.seal(NOTE, key, "secret", Map.of()));
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		locks.put(USER, null);
		assertThrows(IllegalArgumentException.class, () -> NoteEnvelope.seal(NOTE, key, "secret", locks));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 16, 24, 31, 33, 64})
	void sealRejectsWrappingKeysThatAreNot32Bytes(int length) {
		assertThrows(IllegalArgumentException.class,
				() -> NoteEnvelope.seal(NOTE, key, "secret", Map.of(USER, new byte[length])));
	}

	@Test
	void destroyedContentKeyCannotBeCopied() {
		assertFalse(key.isDestroyed());
		assertEquals(32, key.copy().length);
		key.destroy();
		assertTrue(key.isDestroyed());
		assertThrows(IllegalStateException.class, key::copy);
	}

	@Test
	void randomContentKeysDiffer() {
		ContentKey first = ContentKey.random();
		ContentKey second = ContentKey.random();
		byte[] firstBytes = first.copy();
		byte[] secondBytes = second.copy();
		try {
			assertEquals(32, firstBytes.length);
			assertEquals(32, secondBytes.length);
			assertFalse(Arrays.equals(firstBytes, secondBytes));
		} finally {
			first.destroy();
			second.destroy();
			NoteCipher.wipe(firstBytes);
			NoteCipher.wipe(secondBytes);
		}
	}

	private String seal(String plaintext) {
		return NoteEnvelope.seal(NOTE, key, plaintext, Map.of(USER, wrappingKey));
	}

	private static void assertUnlocks(UUID note, String envelope, NoteEnvelope.Lock lock, byte[] wrappingKey,
			String plaintext) throws WrongKeyException {
		ContentKey unlocked = NoteEnvelope.unlock(note, envelope, lock, wrappingKey);
		try {
			assertEquals(plaintext, NoteEnvelope.decrypt(note, envelope, unlocked));
		} finally {
			unlocked.destroy();
		}
	}

	private static byte[] keyBytes(int value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	// 密码锁标识最后一段（验证码的 SHA-256）：算包装钥匙时不看它的值
	private static final String CHECK = "," + Base64.getEncoder().encodeToString(new byte[32]);

	private static NoteEnvelope.Lock passwordLock(String id) {
		return new NoteEnvelope.Lock(NoteEnvelope.Kind.PASSWORD, id);
	}

	private static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String header(String envelope) {
		return envelope.substring(0, envelope.indexOf('|'));
	}

	private static String payload(String envelope) {
		return envelope.substring(envelope.indexOf('|') + 1);
	}

	private static String wrap(String envelope, int index) {
		String entry = header(envelope).substring(NoteEnvelope.PREFIX.length()).split(";", -1)[index];
		return entry.substring(entry.lastIndexOf('.') + 1);
	}

	private static String text(String entries, String body) {
		return "MPAL2:" + entries + "|" + body;
	}

	private static byte[] nonce(String encoded) {
		return Arrays.copyOf(Base64.getDecoder().decode(encoded), 12);
	}

	private static String flipCharacter(String encoded, int index) {
		char changed = encoded.charAt(index) == 'A' ? 'B' : 'A';
		return encoded.substring(0, index) + changed + encoded.substring(index + 1);
	}

	private static String nonzeroPaddingBits(String encoded) {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		int index = encoded.indexOf('=') - 1;
		char changed = alphabet.charAt(alphabet.indexOf(encoded.charAt(index)) | 1);
		return encoded.substring(0, index) + changed + encoded.substring(index + 1);
	}
}
