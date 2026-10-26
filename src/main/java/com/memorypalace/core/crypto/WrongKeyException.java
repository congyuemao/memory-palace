package com.memorypalace.core.crypto;

/** 密码 / 恢复密钥不对，或者密文被篡改、和笔记对不上。 */
public final class WrongKeyException extends Exception {
	public WrongKeyException(String message) {
		super(message);
	}
}
