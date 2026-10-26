package com.memorypalace.core.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 加密 / 解密一篇笔记的正文。密文是一行文本，放在笔记文件的 front matter 之后：
 * <pre>MPAL1:&lt;Base64(nonce 12 字节 || AES-256-GCM 密文和 16 字节 tag)&gt;</pre>
 * 要求：
 * - 每次加密都用新的 SecureRandom nonce
 * - GCM 的附加认证数据（AAD）= "MPAL1|" + noteId：密文和这篇笔记绑定，换到别的笔记文件里会解密失败
 * - 正文按 UTF-8 编码
 * - Base64 用标准字母表（java.util.Base64.getEncoder()），不换行
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteCipher {
	public static final String PREFIX = "MPAL1:";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int NONCE_LENGTH = 12;
	private static final int TAG_LENGTH = 16;

	private NoteCipher() {}

	/** 正文是不是密文：去掉首尾空白后以 PREFIX 开头。 */
	public static boolean isEncrypted(String body) {
		return body != null && body.strip().startsWith(PREFIX);
	}

	public static String encrypt(MasterKey key, UUID noteId, String plaintext) {
		Objects.requireNonNull(noteId, "noteId");
		byte[] secret = copyKey(key);
		byte[] plain = null;
		try {
			plain = plaintext.getBytes(StandardCharsets.UTF_8);
			return PREFIX + Base64.getEncoder().encodeToString(seal(secret, aad(noteId), plain));
		} finally {
			wipe(secret);
			wipe(plain);
		}
	}

	/** 密钥不对、密文被改过、Base64 坏了、和 noteId 对不上 → WrongKeyException。允许首尾有空白/换行。 */
	public static String decrypt(MasterKey key, UUID noteId, String ciphertext) throws WrongKeyException {
		Objects.requireNonNull(noteId, "noteId");
		byte[] secret = copyKey(key);
		byte[] plain = null;
		byte[] packed = null;
		try {
			if (!isEncrypted(ciphertext)) {
				throw wrongKey();
			}
			try {
				packed = Base64.getDecoder().decode(ciphertext.strip().substring(PREFIX.length()));
			} catch (IllegalArgumentException e) {
				throw wrongKey();
			}
			plain = open(secret, aad(noteId), packed);
			return new String(plain, StandardCharsets.UTF_8);
		} finally {
			wipe(secret);
			wipe(plain);
			wipe(packed);
		}
	}

	private static byte[] aad(UUID noteId) {
		return ("MPAL1|" + noteId).getBytes(StandardCharsets.UTF_8);
	}

	static byte[] copyKey(MasterKey key) {
		// 与 destroy 共用锁，避免复制到一半被清零；绝不清零调用方的主密钥。
		synchronized (key) {
			return key.bytes().clone();
		}
	}

	static byte[] seal(byte[] key, byte[] aad, byte[] plain) {
		byte[] nonce = new byte[NONCE_LENGTH];
		RANDOM.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			// 每次独立随机 nonce，128 位认证标签；不复用 Cipher 实例。
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			cipher.updateAAD(aad);
			byte[] encrypted = cipher.doFinal(plain);
			byte[] packed = Arrays.copyOf(nonce, NONCE_LENGTH + encrypted.length);
			System.arraycopy(encrypted, 0, packed, NONCE_LENGTH, encrypted.length);
			return packed;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 AES-GCM 加密");
		}
	}

	static byte[] open(byte[] key, byte[] aad, byte[] packed) throws WrongKeyException {
		if (packed.length < NONCE_LENGTH + TAG_LENGTH) {
			throw wrongKey();
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(128, packed, 0, NONCE_LENGTH));
			cipher.updateAAD(aad);
			// 只由 JDK GCM 验证标签，不另存密码校验值，也不提前返回明文。
			return cipher.doFinal(packed, NONCE_LENGTH, packed.length - NONCE_LENGTH);
		} catch (AEADBadTagException e) {
			throw wrongKey();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 AES-GCM 解密");
		}
	}

	static WrongKeyException wrongKey() {
		return new WrongKeyException("无法解密：密钥不正确或数据已损坏");
	}

	static void wipe(byte[] bytes) {
		if (bytes != null) {
			Arrays.fill(bytes, (byte) 0);
		}
	}
}
