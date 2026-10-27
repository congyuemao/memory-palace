package com.memorypalace.core.crypto;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 阅读密码的防爆破限速（服务端用），按"谁 + 哪篇"分开计：
 * - 一分钟内可以错 5 次；错满 5 次就要等一段时间才能再试
 * - 等待时间逐次翻倍：1、2、4、8……分钟，最多 1 小时
 * - 猜对了清零；一天没再错过也清零
 * 只记在内存里，不随玩家下线清空（否则重进服务器就能绕过）；服务器重启会清空。
 * 线程安全。注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class PasswordThrottle<K> {
	public static final int FREE_FAILURES = 5;
	private static final long SECOND = 1_000_000_000L;
	private static final long WINDOW = 60 * SECOND;
	private static final long FIRST_WAIT = 60 * SECOND;
	private static final long MAX_WAIT = 3600 * SECOND;
	private static final long FORGET_AFTER = 24 * 3600 * SECOND;
	private static final int MAX_ENTRIES = 10_000;

	private record State(int failures, long windowStart, int lockouts, long lockedUntil, long lastFailure) {}

	private final LongSupplier nanoClock;
	private final Map<K, State> states = new HashMap<>();

	public PasswordThrottle() {
		this(System::nanoTime);
	}

	/** 测试用：自己给时钟（纳秒）。 */
	public PasswordThrottle(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	/** 现在还要等几秒才能试（向上取整）；0 = 可以试。记录表满了、又是没见过的人时也让等（不会因为表满就放开限速）。 */
	public synchronized long waitSeconds(K key) {
		long now = nanoClock.getAsLong();
		State state = current(key, now);
		if (state == null) {
			return states.size() >= MAX_ENTRIES && !prune(now) ? WINDOW / SECOND : 0;
		}
		long left = state.lockedUntil() - now;
		return left <= 0 ? 0 : (left + SECOND - 1) / SECOND;
	}

	/** 猜错一次。 */
	public synchronized void failure(K key) {
		long now = nanoClock.getAsLong();
		State state = current(key, now);
		if (state == null) {
			if (states.size() >= MAX_ENTRIES && !prune(now)) {
				return; // 表满了：waitSeconds 对新来的人一律让等，这里不用再记
			}
			state = new State(0, now, 0, 0, now);
		}
		int failures = state.failures();
		long windowStart = state.windowStart();
		if (now - windowStart >= WINDOW) {
			failures = 0;
			windowStart = now;
		}
		failures++;
		int lockouts = state.lockouts();
		long lockedUntil = state.lockedUntil();
		if (failures >= FREE_FAILURES) {
			long wait = FIRST_WAIT << Math.min(lockouts, 6); // 1 分钟 × 2^n，下面再封顶
			lockouts++;
			lockedUntil = now + Math.min(wait, MAX_WAIT);
			failures = 0;
			windowStart = lockedUntil; // 等完之后重新给 5 次
		}
		states.put(key, new State(failures, windowStart, lockouts, lockedUntil, now));
	}

	/** 猜对了：清零。 */
	public synchronized void success(K key) {
		states.remove(key);
	}

	// 一天没再错过的记录当作不存在（并顺手删掉）
	private State current(K key, long now) {
		State state = states.get(key);
		if (state != null && now - state.lastFailure() >= FORGET_AFTER && now - state.lockedUntil() >= 0) {
			states.remove(key);
			return null;
		}
		return state;
	}

	// 表满时先清掉"没在等、一分钟内也没错过"的记录；清出空位返回 true
	private boolean prune(long now) {
		states.values().removeIf(s -> now - s.lockedUntil() >= 0 && now - s.lastFailure() >= WINDOW);
		return states.size() < MAX_ENTRIES;
	}
}
