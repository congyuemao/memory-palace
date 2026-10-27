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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InboxCipherTest {
	private final InboxCipher.BoxKeys keys = InboxCipher.generate();
	private final InboxCipher.BoxKeys other = InboxCipher.generate();

	@AfterEach
	void wipePrivateKeys() {
		NoteCipher.wipe(keys.privateKey());
		NoteCipher.wipe(other.privateKey());
	}

	@Test
	void generatedKeysHave44BytePublicKeysAndDifferEveryTime() {
		assertEquals(44, keys.publicKey().length);
		assertEquals(44, other.publicKey().length);
		assertFalse(Arrays.equals(keys.publicKey(), other.publicKey()));
		assertFalse(Arrays.equals(keys.privateKey(), other.privateKey()));
	}

	@ParameterizedTest(name = "round trip: {0}")
	@MethodSource("plaintexts")
	void sealRoundTripsAndUsesFreshCiphertext(String description, String plaintext) throws Exception {
		String first = InboxCipher.seal(keys.publicKey(), plaintext);
		String second = InboxCipher.seal(keys.publicKey(), plaintext);
		assertNotEquals(first, second);
		for (String sealed : new String[]{first, second}) {
			assertTrue(sealed.startsWith(InboxCipher.PREFIX));
			assertTrue(InboxCipher.isSealed(sealed));
			assertEquals(plaintext, open(sealed));
		}
		assertEquals(plaintext, open(" \t\r\n" + first + "\r\n "));
	}

	static Stream<Arguments> plaintexts() {
		return Stream.of(
				Arguments.of("Chinese", "今天学了特征值和奇异值"),
				Arguments.of("emoji", "😀🔐📬"),
				Arguments.of("empty", ""),
				Arguments.of("2000 characters", "字".repeat(2000)));
	}

	@Test
	void wrongPrivateKeyOrWrongRecipientPublicKeyCannotOpen() {
		String sealed = InboxCipher.seal(keys.publicKey(), "只给收件人");
		assertAll(
				() -> assertThrows(WrongKeyException.class,
						() -> InboxCipher.open(other.privateKey(), keys.publicKey(), sealed)),
				() -> assertThrows(WrongKeyException.class,
						() -> InboxCipher.open(keys.privateKey(), other.publicKey(), sealed)));
	}

	@ParameterizedTest(name = "tamper {0}")
	@CsvSource({"X.509 header, 0", "ephemeral point, 16", "nonce, 60", "ciphertext, 76", "tag, 112"})
	void changedBase64CharacterIsRejected(String part, int index) {
		// Packed bytes: public key [0,44), nonce [44,56), ciphertext [56,77), tag [77,93).
		String sealed = InboxCipher.seal(keys.publicKey(), "authenticated message");
		String damaged = InboxCipher.PREFIX + flipCharacter(payload(sealed), index);
		assertTrue(InboxCipher.isSealed(damaged));
		assertThrows(WrongKeyException.class, () -> open(damaged));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 43, 44, 55, 56, 71, 92})
	void truncatedKeyNonceOrCiphertextIsRejected(int length) {
		String sealed = InboxCipher.seal(keys.publicKey(), "authenticated message");
		byte[] packed = Base64.getDecoder().decode(payload(sealed));
		String truncated = InboxCipher.PREFIX + Base64.getEncoder().encodeToString(Arrays.copyOf(packed, length));
		assertThrows(WrongKeyException.class, () -> open(truncated));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void noncanonicalBase64IsRejectedEvenWhenItDecodesToAuthenticCiphertext(int plaintextLength) {
		// Exercise both one and two padding characters, keeping the authenticated bytes intact.
		String encoded = payload(InboxCipher.seal(keys.publicKey(), "x".repeat(plaintextLength)));
		String unpadded = encoded.replace("=", "");
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		int last = encoded.indexOf('=') - 1;
		char changed = alphabet.charAt(alphabet.indexOf(encoded.charAt(last)) | 1);
		String paddingBits = encoded.substring(0, last) + changed + encoded.substring(last + 1);
		assertArrayEquals(Base64.getDecoder().decode(encoded), Base64.getDecoder().decode(unpadded));
		assertArrayEquals(Base64.getDecoder().decode(encoded), Base64.getDecoder().decode(paddingBits));
		assertAll(
				() -> assertThrows(WrongKeyException.class, () -> open(InboxCipher.PREFIX + unpadded)),
				() -> assertThrows(WrongKeyException.class, () -> open(InboxCipher.PREFIX + paddingBits)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"extra padding", "trailing data", "internal space", "illegal character", "invalid length"})
	void invalidBase64IsReportedAsWrongKey(String corruption) {
		String valid = payload(InboxCipher.seal(keys.publicKey(), "x"));
		String encoded = switch (corruption) {
			case "extra padding" -> valid + "=";
			case "trailing data" -> valid + "A";
			case "internal space" -> valid.substring(0, 16) + " " + valid.substring(16);
			case "illegal character" -> valid.substring(0, 16) + "!" + valid.substring(16);
			case "invalid length" -> "A";
			default -> throw new AssertionError(corruption);
		};
		assertThrows(WrongKeyException.class, () -> open(InboxCipher.PREFIX + encoded));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1})
	void lowOrderRecipientAndEphemeralPointsAreRejectedWithTheExpectedException(int coordinate) {
		byte[] lowOrder = keys.publicKey().clone();
		Arrays.fill(lowOrder, 12, 44, (byte) 0); // Preserve the real X.509 header, replace only the point.
		lowOrder[12] = (byte) coordinate;
		String sealed = InboxCipher.seal(keys.publicKey(), "secret");
		byte[] packed = Base64.getDecoder().decode(payload(sealed));
		System.arraycopy(lowOrder, 0, packed, 0, lowOrder.length);
		String malicious = InboxCipher.PREFIX + Base64.getEncoder().encodeToString(packed);
		assertTrue(InboxCipher.isSealed(malicious));
		assertAll(
				() -> assertThrowsExactly(WrongKeyException.class, () -> open(malicious)),
				() -> assertThrowsExactly(IllegalArgumentException.class, () -> InboxCipher.seal(lowOrder, "secret")));
	}

	@ParameterizedTest(name = "invalid public key: {0}")
	@MethodSource("invalidPublicKeys")
	void sealAndFingerprintRejectInvalidPublicKeys(String description, byte[] publicKey) {
		assertAll(
				() -> assertThrows(IllegalArgumentException.class, () -> InboxCipher.seal(publicKey, "secret")),
				() -> assertThrows(IllegalArgumentException.class, () -> InboxCipher.fingerprint(publicKey)));
	}

	static Stream<Arguments> invalidPublicKeys() {
		return Stream.of(
				Arguments.of("null", (byte[]) null),
				Arguments.of("empty", new byte[0]),
				Arguments.of("raw point without X.509 header", new byte[32]),
				Arguments.of("too short", new byte[43]),
				Arguments.of("too long", new byte[45]),
				Arguments.of("44 bytes of garbage", new byte[44]));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"plain text", "MPALBOX1:", "MPALBOX1:AA AA", "MPALBOX1:AA\nAA",
			"MPALBOX1:AA!AA", "MPALBOX1:AA_AA"})
	void nonSealedTextIsNotRecognizedAndCannotBeOpened(String line) {
		assertFalse(InboxCipher.isSealed(line));
		assertThrows(WrongKeyException.class, () -> open(line));
	}

	@Test
	void isSealedAllowsSurroundingWhitespaceButRejectsOversizedLines() {
		String sealed = InboxCipher.seal(keys.publicKey(), "secret");
		assertTrue(InboxCipher.isSealed(" \t\r\n" + sealed + "\r\n "));
		String oversized = InboxCipher.PREFIX + "A".repeat(InboxCipher.MAX_SEALED_CHARS - InboxCipher.PREFIX.length() + 1);
		assertFalse(InboxCipher.isSealed(oversized));
		assertThrows(WrongKeyException.class, () -> open(oversized));
	}

	@Test
	void hasSealedLinesFindsAWholeSealedLineAmongPlainLines() {
		String sealed = InboxCipher.seal(keys.publicKey(), "secret");
		assertTrue(InboxCipher.hasSealedLines("# 收件箱\r\nplain\r\n  " + sealed + "\t\r\nlast\r\n"));
		assertFalse(InboxCipher.hasSealedLines(null));
		assertFalse(InboxCipher.hasSealedLines(""));
		assertFalse(InboxCipher.hasSealedLines("plain\nMPALBOX1:\n"));
		assertFalse(InboxCipher.hasSealedLines("quoted: " + sealed));
	}

	@ParameterizedTest(name = "openAll preserves line separator {index}")
	@ValueSource(strings = {"\n", "\r\n"})
	void openAllPreservesPlainLinesAndBreaksAndReplacesUnreadableLines(String newline) {
		String first = InboxCipher.seal(keys.publicKey(), "第一条 😀");
		String last = InboxCipher.seal(keys.publicKey(), "最后一条");
		String wrongRecipient = InboxCipher.seal(other.publicKey(), "别人的内容");
		String damaged = InboxCipher.PREFIX + flipCharacter(payload(first), 60);
		String body = String.join(newline, "# 收件箱", "", first, "  手写的一行\t", wrongRecipient,
				damaged, last, "", "");
		String expected = String.join(newline, "# 收件箱", "", "第一条 😀", "  手写的一行\t", "[无法解密]",
				"[无法解密]", "最后一条", "", "");
		assertEquals(expected, InboxCipher.openAll(keys.privateKey(), keys.publicKey(), body, "[无法解密]"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "\n", "\n\n", "\nplain\n\n", "  plain\t\r\n\r\nlast\r\n"})
	void openAllPreservesPlainOnlyBodiesExactly(String body) {
		assertEquals(body, InboxCipher.openAll(keys.privateKey(), keys.publicKey(), body, "[unreadable]"));
	}

	@Test
	void openAllPreservesLineBreaksAfterAnEmptyDecryptedFirstLine() {
		String sealed = InboxCipher.seal(keys.publicKey(), "");
		assertEquals("\nplain\n", InboxCipher.openAll(keys.privateKey(), keys.publicKey(), sealed + "\nplain\n", "?"));
	}

	@Test
	void fingerprintIsStableDistinctAndHasEightLowercaseHexGroups() {
		String fingerprint = InboxCipher.fingerprint(keys.publicKey());
		assertEquals(fingerprint, InboxCipher.fingerprint(keys.publicKey().clone()));
		assertNotEquals(fingerprint, InboxCipher.fingerprint(other.publicKey()));
		assertTrue(fingerprint.matches("[0-9a-f]{4}(-[0-9a-f]{4}){7}"), fingerprint);
	}

	private String open(String sealed) throws WrongKeyException {
		return InboxCipher.open(keys.privateKey(), keys.publicKey(), sealed);
	}

	private static String payload(String sealed) {
		return sealed.substring(InboxCipher.PREFIX.length());
	}

	private static String flipCharacter(String encoded, int index) {
		char changed = encoded.charAt(index) == 'A' ? 'B' : 'A';
		return encoded.substring(0, index) + changed + encoded.substring(index + 1);
	}
}
