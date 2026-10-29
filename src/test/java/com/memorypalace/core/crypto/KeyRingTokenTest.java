package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 钥匙圈上的实体钥匙锁。钥匙算出来的结果（PRF）在这里用固定字节代替，Windows 那部分要拿真钥匙实测。 */
class KeyRingTokenTest {
	private static final byte[] CREDENTIAL = bytes(80, 7);
	private static final byte[] PRF = bytes(32, 42);

	private static char[] pw(String s) {
		return s.toCharArray();
	}

	private static byte[] bytes(int length, int seed) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) {
			b[i] = (byte) (seed + i * 31);
		}
		return b;
	}

	@Test
	void tokenUnlocksTheSameMasterKeyAndPasswordStillWorks() throws Exception {
		KeyRing.Created created = KeyRing.create(pw("password1"), true);
		byte[] salt = KeyRing.newTokenSalt();
		String content = KeyRing.withToken(created.content(), created.key(), CREDENTIAL, salt, PRF);

		KeyRing.Token token = KeyRing.token(content);
		assertArrayEquals(CREDENTIAL, token.credentialId());
		assertArrayEquals(salt, token.salt());
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithToken(content, PRF).bytes());
		assertArrayEquals(created.key().bytes(), KeyRing.unlock(content, pw("password1")).bytes(), "密码照常能用");
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithRecovery(content, created.recoveryKey()).bytes(), "恢复密钥照常能用");
		assertTrue(KeyRing.hasBox(content));
	}

	@Test
	void wrongTokenOrTamperedFieldsDoNotUnlock() {
		KeyRing.Created created = KeyRing.create(pw("password1"), false);
		String content = KeyRing.withToken(created.content(), created.key(), CREDENTIAL, KeyRing.newTokenSalt(), PRF);
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(content, bytes(32, 43)), "别的钥匙算出来的不一样");
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(content, bytes(31, 42)));
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(created.content(), PRF), "没登记实体钥匙");

		// 换掉凭据 ID：包装绑定了凭据，解不开
		String swapped = content.replace("token.credential=" + java.util.Base64.getEncoder().encodeToString(CREDENTIAL),
				"token.credential=" + java.util.Base64.getEncoder().encodeToString(bytes(80, 8)));
		assertNotEquals(content, swapped);
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(swapped, PRF));
	}

	@Test
	void tokenSurvivesPasswordAndRecoveryChangesAndCanBeRemoved() throws Exception {
		KeyRing.Created created = KeyRing.create(pw("password1"), true);
		String content = KeyRing.withToken(created.content(), created.key(), CREDENTIAL, KeyRing.newTokenSalt(), PRF);

		String changed = KeyRing.changePassword(content, created.key(), pw("password2"));
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithToken(changed, PRF).bytes(), "改密码后实体钥匙还能用");
		KeyRing.Created regenerated = KeyRing.regenerateRecovery(changed, pw("password2"));
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithToken(regenerated.content(), PRF).bytes());
		String noRecovery = KeyRing.removeRecovery(regenerated.content(), pw("password2"));
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithToken(noRecovery, PRF).bytes());

		String removed = KeyRing.withoutToken(noRecovery);
		assertNull(KeyRing.token(removed));
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(removed, PRF));
		assertArrayEquals(created.key().bytes(), KeyRing.unlock(removed, pw("password2")).bytes());
		assertEquals(removed, KeyRing.withoutToken(removed), "没登记时原样返回");
	}

	@Test
	void reRegisteringReplacesTheOldToken() throws Exception {
		KeyRing.Created created = KeyRing.create(pw("password1"), false);
		String first = KeyRing.withToken(created.content(), created.key(), CREDENTIAL, KeyRing.newTokenSalt(), PRF);
		byte[] other = bytes(64, 99);
		byte[] otherPrf = bytes(32, 5);
		String second = KeyRing.withToken(first, created.key(), other, KeyRing.newTokenSalt(), otherPrf);
		assertArrayEquals(other, KeyRing.token(second).credentialId());
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithToken(second, PRF));
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithToken(second, otherPrf).bytes());
	}

	@Test
	void folderKeyRingsAndBadInputsAreRejected() {
		KeyRing.Created folder = KeyRing.createFolder(pw("password1"), false);
		assertThrows(IllegalArgumentException.class,
				() -> KeyRing.withToken(folder.content(), folder.key(), CREDENTIAL, KeyRing.newTokenSalt(), PRF));
		KeyRing.Created created = KeyRing.create(pw("password1"), false);
		assertThrows(IllegalArgumentException.class,
				() -> KeyRing.withToken(created.content(), created.key(), new byte[0], KeyRing.newTokenSalt(), PRF));
		assertThrows(IllegalArgumentException.class,
				() -> KeyRing.withToken(created.content(), created.key(), CREDENTIAL, new byte[16], PRF));

		// 字段只出现一部分：格式错误
		String content = KeyRing.withToken(created.content(), created.key(), CREDENTIAL, KeyRing.newTokenSalt(), PRF);
		String partial = Arrays.stream(content.split("\n")).filter(l -> !l.startsWith("token.wrap=")).reduce("", (a, b) -> a + b + "\n");
		assertThrows(IllegalArgumentException.class, () -> KeyRing.token(partial));
		assertThrows(IllegalArgumentException.class, () -> KeyRing.unlock(partial, pw("password1")));
	}
}
