package com.memorypalace.core.crypto;

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

	private NoteCipher() {}

	/** 正文是不是密文：去掉首尾空白后以 PREFIX 开头。 */
	public static boolean isEncrypted(String body) {
		throw new UnsupportedOperationException("TODO");
	}

	public static String encrypt(MasterKey key, UUID noteId, String plaintext) {
		throw new UnsupportedOperationException("TODO");
	}

	/** 密钥不对、密文被改过、Base64 坏了、和 noteId 对不上 → WrongKeyException。允许首尾有空白/换行。 */
	public static String decrypt(MasterKey key, UUID noteId, String ciphertext) throws WrongKeyException {
		throw new UnsupportedOperationException("TODO");
	}
}
