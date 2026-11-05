package com.memorypalace.core.vault;

/** 可以展示给玩家的校验失败。key 对应语言文件里的 error.memorypalace.<key>；message 只用于日志和测试。 */
public final class LocalizedFailure extends IllegalArgumentException {
	private final String key;

	public LocalizedFailure(String key, String message) {
		super(message);
		this.key = key;
	}

	public LocalizedFailure(String key, String message, Throwable cause) {
		super(message, cause);
		this.key = key;
	}

	public String key() {
		return key;
	}
}
