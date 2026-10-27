package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PasswordThrottleTest {
	private static final long SECOND = 1_000_000_000L;
	private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
	private final PasswordThrottle<String> throttle = new PasswordThrottle<>(clock::get);

	private void advance(long seconds) {
		clock.addAndGet(seconds * SECOND);
	}

	private void fail(String key, int times) {
		for (int i = 0; i < times; i++) {
			assertEquals(0, throttle.waitSeconds(key), "第 " + (i + 1) + " 次应该还能试");
			throttle.failure(key);
		}
	}

	@Test
	void fiveFailuresAreFreeThenTheWaitDoublesUpToAnHour() {
		fail("a", 4);
		assertEquals(0, throttle.waitSeconds("a"));
		throttle.failure("a");
		assertEquals(60, throttle.waitSeconds("a"));
		advance(59);
		assertEquals(1, throttle.waitSeconds("a"));
		advance(1);
		fail("a", 5);
		assertEquals(120, throttle.waitSeconds("a"));
		long[] expected = {240, 480, 960, 1920, 3600, 3600};
		for (long wait : expected) {
			advance(throttle.waitSeconds("a"));
			fail("a", 5);
			assertEquals(wait, throttle.waitSeconds("a"));
		}
	}

	@Test
	void failuresOutsideTheOneMinuteWindowDoNotAddUp() {
		fail("a", 4);
		advance(60);
		fail("a", 4);
		assertEquals(0, throttle.waitSeconds("a"));
		throttle.failure("a");
		assertEquals(60, throttle.waitSeconds("a"));
	}

	@Test
	void successClearsEverythingAndKeysAreIndependent() {
		fail("a", 5);
		assertEquals(60, throttle.waitSeconds("a"));
		assertEquals(0, throttle.waitSeconds("b"));
		throttle.success("a");
		assertEquals(0, throttle.waitSeconds("a"));
		fail("a", 5);
		assertEquals(60, throttle.waitSeconds("a"), "清零后从 1 分钟重新算");
	}

	@Test
	void lockoutsAreForgottenAfterADayWithoutFailures() {
		fail("a", 5);
		advance(60);
		fail("a", 5);
		assertEquals(120, throttle.waitSeconds("a"));
		advance(24 * 3600);
		assertEquals(0, throttle.waitSeconds("a"));
		fail("a", 5);
		assertEquals(60, throttle.waitSeconds("a"));
	}
}
