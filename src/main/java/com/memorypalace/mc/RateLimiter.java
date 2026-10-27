package com.memorypalace.mc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个玩家一个"令牌桶"：每秒补 perSecond 个额度，最多攒 burst 个；做一次事花一个。
 * 正常使用（打开一篇图多的笔记、连着保存几次）够用，一直刷包就会被挡住。
 */
final class RateLimiter {
	private record Bucket(double tokens, long atNanos) {}

	private final double perSecond;
	private final double burst;
	private final java.util.function.LongSupplier nanoClock;
	private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

	RateLimiter(double perSecond, double burst) {
		this(perSecond, burst, System::nanoTime);
	}

	/** 测试用：自己给时钟（纳秒）。 */
	RateLimiter(double perSecond, double burst, java.util.function.LongSupplier nanoClock) {
		this.perSecond = perSecond;
		this.burst = burst;
		this.nanoClock = nanoClock;
	}

	/** 还有额度就花掉一个并返回 true。 */
	boolean tryTake(UUID player) {
		long now = nanoClock.getAsLong();
		boolean[] ok = new boolean[1];
		buckets.compute(player, (id, b) -> {
			double tokens = b == null ? burst : Math.min(burst, b.tokens() + (now - b.atNanos()) / 1e9 * perSecond);
			ok[0] = tokens >= 1;
			return new Bucket(ok[0] ? tokens - 1 : tokens, now);
		});
		return ok[0];
	}

	/** 玩家下线时调用，免得表一直变大。 */
	void forget(UUID player) {
		buckets.remove(player);
	}
}
