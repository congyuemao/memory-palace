package com.memorypalace.core.crypto;

import java.util.Arrays;

/**
 * 主密钥：32 字节随机数，所有加密笔记都用它加密。只存在于内存里，上锁时调用 destroy() 清零。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class MasterKey {
	public static final int LENGTH = 32;

	private final byte[] bytes;
	private boolean destroyed;

	/** 复制一份 bytes（调用方可以自己清零原数组）。长度必须是 32。 */
	public MasterKey(byte[] bytes) {
		if (bytes.length != LENGTH) {
			throw new IllegalArgumentException("master key must be 32 bytes");
		}
		this.bytes = bytes.clone();
	}

	/** 原始字节（不要保存这个数组的引用）；已销毁时抛 IllegalStateException。 */
	public synchronized byte[] bytes() {
		if (destroyed) {
			throw new IllegalStateException("master key destroyed");
		}
		return bytes;
	}

	public synchronized void destroy() {
		Arrays.fill(bytes, (byte) 0);
		destroyed = true;
	}

	public synchronized boolean isDestroyed() {
		return destroyed;
	}
}
