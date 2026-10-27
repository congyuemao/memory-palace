package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PasswordKdfTest {
	@Test
	void samePasswordSaltAndParametersGiveTheSame32Bytes() {
		char[] password = "密码 😀 correct horse".toCharArray();
		char[] otherPassword = "密码 😀 correct Horse".toCharArray();
		byte[] salt = new byte[16];
		byte[] otherSalt = salt.clone();
		otherSalt[0] = 1;
		byte[] first = null;
		byte[] again = null;
		byte[] changedSalt = null;
		byte[] changedPassword = null;
		try {
			first = PasswordKdf.derive(password, salt, 8, 1, 1);
			again = PasswordKdf.derive(password.clone(), salt.clone(), 8, 1, 1);
			changedSalt = PasswordKdf.derive(password, otherSalt, 8, 1, 1);
			changedPassword = PasswordKdf.derive(otherPassword, salt, 8, 1, 1);
			assertEquals(32, first.length);
			assertEquals(32, again.length);
			assertEquals(32, changedSalt.length);
			assertEquals(32, changedPassword.length);
			assertArrayEquals(first, again);
			assertFalse(Arrays.equals(first, changedSalt));
			assertFalse(Arrays.equals(first, changedPassword));
		} finally {
			Arrays.fill(password, '\0');
			Arrays.fill(otherPassword, '\0');
			NoteCipher.wipe(first);
			NoteCipher.wipe(again);
			NoteCipher.wipe(changedSalt);
			NoteCipher.wipe(changedPassword);
		}
	}

	@Test
	void checkAcceptsTheDefaultsWithoutRunningArgon2() {
		assertTrue(PasswordKdf.check(PasswordKdf.MEMORY_KIB, PasswordKdf.ITERATIONS, PasswordKdf.PARALLELISM));
	}

	@ParameterizedTest(name = "valid parameters: m={0}, t={1}, p={2}")
	@CsvSource({
			"8, 1, 1",
			"16, 1, 2",
			"24, 1, 3",
			"32, 1, 4",
			"8, 10, 1",
			"32, 10, 4",
			"262144, 1, 1",
			"262144, 3, 1",
			"262144, 3, 4",
			"196608, 4, 1",
			"131072, 6, 4",
			"78643, 10, 1"
	})
	void checkAcceptsDocumentedEdgesWithoutDeriving(int memory, int iterations, int parallelism) {
		assertTrue(PasswordKdf.check(memory, iterations, parallelism));
	}

	@ParameterizedTest(name = "invalid parameters: m={0}, t={1}, p={2}")
	@MethodSource("invalidParameters")
	void checkAndDeriveRejectOutOfRangeParameters(int memory, int iterations, int parallelism) {
		assertAll(
				() -> assertFalse(PasswordKdf.check(memory, iterations, parallelism)),
				() -> assertThrows(IllegalArgumentException.class,
						() -> PasswordKdf.derive("valid-password".toCharArray(), new byte[16], memory, iterations, parallelism)));
	}

	static Stream<Arguments> invalidParameters() {
		return Stream.of(
				Arguments.of(7, 1, 1),
				Arguments.of(15, 1, 2),
				Arguments.of(23, 1, 3),
				Arguments.of(31, 1, 4),
				Arguments.of(262145, 1, 1),
				Arguments.of(8, 0, 1),
				Arguments.of(8, 11, 1),
				Arguments.of(8, 1, 0),
				Arguments.of(40, 1, 5),
				Arguments.of(262144, 4, 1),
				Arguments.of(196609, 4, 1),
				Arguments.of(78644, 10, 1),
				Arguments.of(0, 1, 1),
				Arguments.of(-1, 1, 1),
				Arguments.of(8, -1, 1),
				Arguments.of(8, 1, -1),
				Arguments.of(Integer.MAX_VALUE, 10, 1),
				Arguments.of(Integer.MIN_VALUE, 1, 1),
				Arguments.of(8, Integer.MAX_VALUE, 1),
				Arguments.of(8, 1, Integer.MAX_VALUE));
	}

	@Test
	void deriveRejectsANullPassword() {
		assertThrows(IllegalArgumentException.class, () -> PasswordKdf.derive(null, new byte[16], 8, 1, 1));
	}

	@ParameterizedTest(name = "invalid salt case {index}")
	@MethodSource("invalidSalts")
	void deriveRejectsNullOrIncorrectSaltLengths(byte[] salt) {
		assertThrows(IllegalArgumentException.class,
				() -> PasswordKdf.derive("valid-password".toCharArray(), salt, 8, 1, 1));
	}

	static Stream<byte[]> invalidSalts() {
		return Stream.of(null, new byte[0], new byte[15], new byte[17], new byte[32]);
	}

	@ParameterizedTest(name = "unpaired surrogate case {index}")
	@MethodSource("malformedPasswords")
	void deriveRejectsUnpairedSurrogatesWithoutChangingThePassword(char[] password) {
		char[] original = password.clone();
		assertThrows(IllegalArgumentException.class, () -> PasswordKdf.derive(password, new byte[16], 8, 1, 1));
		assertArrayEquals(original, password);
	}

	static Stream<char[]> malformedPasswords() {
		return Stream.of(
				new char[]{'a', '\uD800', 'b', 'c', 'd', 'e'},
				new char[]{'a', '\uDC00', 'b', 'c', 'd', 'e'},
				new char[]{'a', 'b', 'c', 'd', 'e', '\uD800'},
				new char[]{'\uDC00', 'a', 'b', 'c', 'd', 'e'});
	}

	@Test
	void deriveDoesNotModifyTheCallersPasswordOrSalt() {
		char[] password = "保持原样 😀".toCharArray();
		char[] originalPassword = password.clone();
		byte[] salt = new byte[16];
		Arrays.fill(salt, (byte) 0x5a);
		byte[] originalSalt = salt.clone();
		byte[] derived = null;
		try {
			derived = PasswordKdf.derive(password, salt, 8, 1, 1);
			assertArrayEquals(originalPassword, password);
			assertArrayEquals(originalSalt, salt);
		} finally {
			Arrays.fill(password, '\0');
			Arrays.fill(originalPassword, '\0');
			NoteCipher.wipe(derived);
		}
	}

	@Test
	void keyRingCreateAndUnlockStillRecoverTheSameUsableMasterKey() throws Exception {
		char[] password = "correct horse 日记 😀".toCharArray();
		var created = KeyRing.create(password, false);
		MasterKey unlocked = null;
		try {
			assertNull(created.recoveryKey());
			assertFalse(KeyRing.hasRecovery(created.content()));
			unlocked = KeyRing.unlock(created.content(), password);
			assertArrayEquals(created.key().bytes(), unlocked.bytes());
			UUID note = UUID.fromString("00000000-0000-4000-8000-000000000001");
			String encrypted = NoteCipher.encrypt(created.key(), note, "回归测试 😀");
			assertEquals("回归测试 😀", NoteCipher.decrypt(unlocked, note, encrypted));
		} finally {
			Arrays.fill(password, '\0');
			created.key().destroy();
			if (unlocked != null) {
				unlocked.destroy();
			}
		}
	}
}
