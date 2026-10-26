package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
	private static final UUID NOTE = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

	private static char[] pw(String s) {
		return s.toCharArray();
	}

	@Test
	void createAndUnlockWithPassword() throws Exception {
		var created = KeyRing.create(pw("correct horse"), false);
		assertNull(created.recoveryKey());
		assertFalse(KeyRing.hasRecovery(created.content()));
		MasterKey again = KeyRing.unlock(created.content(), pw("correct horse"));
		assertArrayEquals(created.key().bytes(), again.bytes());
		assertThrows(WrongKeyException.class, () -> KeyRing.unlock(created.content(), pw("wrong horse")));
	}

	@Test
	void passwordIsNotStoredAnywhere() {
		var created = KeyRing.create(pw("secret-日记-123"), true);
		assertFalse(created.content().contains("secret"), "密码不能以任何形式出现在文件里");
		assertFalse(created.content().contains(Base64.getEncoder().encodeToString("secret-日记-123".getBytes())));
	}

	@Test
	void shortPasswordRejected() {
		assertThrows(IllegalArgumentException.class, () -> KeyRing.create(pw("12345"), false));
	}

	@Test
	void recoveryKeyUnlocksAndHasReadableFormat() throws Exception {
		var created = KeyRing.create(pw("password1"), true);
		String rk = created.recoveryKey();
		assertNotNull(rk);
		assertTrue(rk.matches("[A-Z2-7]{4}(-[A-Z2-7]{1,4})+"), rk);
		assertTrue(KeyRing.hasRecovery(created.content()));

		MasterKey viaRecovery = KeyRing.unlockWithRecovery(created.content(), rk);
		assertArrayEquals(created.key().bytes(), viaRecovery.bytes());
		// 输入时忽略大小写、空格、连字符
		String sloppy = " " + rk.toLowerCase().replace("-", " ") + " ";
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithRecovery(created.content(), sloppy).bytes());

		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithRecovery(created.content(), "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AA"));
		var noRecovery = KeyRing.create(pw("password1"), false);
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithRecovery(noRecovery.content(), rk));
	}

	@Test
	void changePasswordKeepsKeyAndRecovery() throws Exception {
		var created = KeyRing.create(pw("old-password"), true);
		String changed = KeyRing.changePassword(created.content(), created.key(), pw("new-password"));
		assertThrows(WrongKeyException.class, () -> KeyRing.unlock(changed, pw("old-password")));
		assertArrayEquals(created.key().bytes(), KeyRing.unlock(changed, pw("new-password")).bytes());
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithRecovery(changed, created.recoveryKey()).bytes());
	}

	@Test
	void regenerateAndRemoveRecoveryNeedPassword() throws Exception {
		var created = KeyRing.create(pw("password1"), true);
		assertThrows(WrongKeyException.class, () -> KeyRing.regenerateRecovery(created.content(), pw("nope-nope")));
		assertThrows(WrongKeyException.class, () -> KeyRing.removeRecovery(created.content(), pw("nope-nope")));

		var regenerated = KeyRing.regenerateRecovery(created.content(), pw("password1"));
		assertNotEquals(created.recoveryKey(), regenerated.recoveryKey());
		assertThrows(WrongKeyException.class, () -> KeyRing.unlockWithRecovery(regenerated.content(), created.recoveryKey()),
				"旧恢复密钥要失效");
		assertArrayEquals(created.key().bytes(), KeyRing.unlockWithRecovery(regenerated.content(), regenerated.recoveryKey()).bytes());

		String removed = KeyRing.removeRecovery(regenerated.content(), pw("password1"));
		assertFalse(KeyRing.hasRecovery(removed));
		assertArrayEquals(created.key().bytes(), KeyRing.unlock(removed, pw("password1")).bytes());
	}

	@Test
	void sameKeyDifferentPasswordsDifferentFiles() {
		var a = KeyRing.create(pw("password1"), false);
		var b = KeyRing.create(pw("password1"), false);
		assertNotEquals(a.content(), b.content(), "每次的盐和主密钥都应该是随机的");
	}

	@Test
	void badContentIsIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> KeyRing.unlock("垃圾内容", pw("password1")));
	}

	@Test
	void noteRoundTripAndBinding() throws Exception {
		MasterKey key = KeyRing.create(pw("password1"), false).key();
		String plain = "# 日记\n今天学了特征值 $\\lambda$ 😀";
		String c1 = NoteCipher.encrypt(key, NOTE, plain);
		String c2 = NoteCipher.encrypt(key, NOTE, plain);
		assertTrue(c1.startsWith(NoteCipher.PREFIX));
		assertTrue(NoteCipher.isEncrypted("\n " + c1 + "\n"));
		assertFalse(NoteCipher.isEncrypted(plain));
		assertNotEquals(c1, c2, "每次加密 nonce 不同");
		assertFalse(c1.contains("日记"));

		assertEquals(plain, NoteCipher.decrypt(key, NOTE, c1));
		assertEquals(plain, NoteCipher.decrypt(key, NOTE, "\r\n" + c1 + "\r\n"));
		assertThrows(WrongKeyException.class, () -> NoteCipher.decrypt(key, OTHER, c1), "换到别的笔记里要失败");

		MasterKey otherKey = KeyRing.create(pw("password2"), false).key();
		assertThrows(WrongKeyException.class, () -> NoteCipher.decrypt(otherKey, NOTE, c1));
	}

	@Test
	void tamperingDetected() throws Exception {
		MasterKey key = KeyRing.create(pw("password1"), false).key();
		String c = NoteCipher.encrypt(key, NOTE, "不能被偷偷改");
		byte[] raw = Base64.getDecoder().decode(c.substring(NoteCipher.PREFIX.length()));
		raw[raw.length - 5] ^= 1;
		String tampered = NoteCipher.PREFIX + Base64.getEncoder().encodeToString(raw);
		assertThrows(WrongKeyException.class, () -> NoteCipher.decrypt(key, NOTE, tampered));
		assertThrows(WrongKeyException.class, () -> NoteCipher.decrypt(key, NOTE, NoteCipher.PREFIX + "不是base64!!"));
	}

	@Test
	void destroyedKeyCannotBeUsed() {
		MasterKey key = KeyRing.create(pw("password1"), false).key();
		key.destroy();
		assertTrue(key.isDestroyed());
		assertThrows(IllegalStateException.class, () -> NoteCipher.encrypt(key, NOTE, "x"));
	}
}
