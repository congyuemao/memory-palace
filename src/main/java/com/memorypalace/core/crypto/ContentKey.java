package com.memorypalace.core.crypto;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 内容钥匙：每篇 MPAL2 加密笔记自己的一把 32 字节随机钥匙，正文用它加密；它本身再被各把"锁"包起来存在笔记里
 * （见 NoteEnvelope）。只存在于内存里，用完调用 destroy() 清零。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class ContentKey {
	public static final int LENGTH = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] bytes;
	private boolean destroyed;

	/** 复制一份 bytes（调用方可以自己清零原数组）。长度必须是 32。 */
	ContentKey(byte[] bytes) {
		if (bytes.length != LENGTH) {
			throw new IllegalArgumentException("content key must be 32 bytes");
		}
		this.bytes = bytes.clone();
	}

	/** 新的随机内容钥匙。 */
	public static ContentKey random() {
		byte[] fresh = new byte[LENGTH];
		RANDOM.nextBytes(fresh);
		try {
			return new ContentKey(fresh);
		} finally {
			NoteCipher.wipe(fresh);
		}
	}

	/** 复制一份原始字节（调用方用完清零）；已销毁时抛 IllegalStateException。 */
	synchronized byte[] copy() {
		if (destroyed) {
			throw new IllegalStateException("content key destroyed");
		}
		return bytes.clone();
	}

	public synchronized void destroy() {
		Arrays.fill(bytes, (byte) 0);
		destroyed = true;
	}

	public synchronized boolean isDestroyed() {
		return destroyed;
	}
}
