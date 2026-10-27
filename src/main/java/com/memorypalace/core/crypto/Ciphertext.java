package com.memorypalace.core.crypto;

/**
 * 一篇笔记的正文是不是密文：旧格式 MPAL1（主密钥直接加密），或新格式 MPAL2（信封加密，比如文件夹密码）。
 * 服务端判断"这是加密笔记"、拒绝把密文当明文处理，都用这里，免得只认得其中一种。
 * 收件箱里的随手记密文行（MPALBOX1）不算：那是夹在明文笔记里的单独几行，见 InboxCipher。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class Ciphertext {
	private Ciphertext() {}

	public static boolean isEncrypted(String body) {
		return NoteCipher.isEncrypted(body) || NoteEnvelope.isEnvelope(body);
	}
}
