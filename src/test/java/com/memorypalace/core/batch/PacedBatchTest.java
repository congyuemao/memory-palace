package com.memorypalace.core.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacedBatchTest {
	@Test
	void sendsOneAtATimeInOrder() {
		var batch = new PacedBatch<String>(1000, 3);
		batch.add("a");
		batch.add("b");
		assertEquals("a", batch.next(0).orElseThrow());
		assertTrue(batch.next(0).isEmpty(), "上一个没结果之前不发下一个");
		batch.result(PacedBatch.Outcome.OK, 0);
		assertEquals("b", batch.next(0).orElseThrow());
		batch.result(PacedBatch.Outcome.OK, 0);
		assertTrue(batch.idle());
		assertEquals(new PacedBatch.Counts(2, 2, 0, 0), batch.counts());
	}

	@Test
	void busyRetriesTheSameItemAfterTheDelay() {
		var batch = new PacedBatch<String>(1000, 3);
		batch.add("a");
		batch.add("b");
		assertEquals("a", batch.next(0).orElseThrow());
		batch.result(PacedBatch.Outcome.BUSY, 0);
		assertTrue(batch.next(999).isEmpty(), "还没到重试时间");
		assertEquals("a", batch.next(1000).orElseThrow(), "重试的是同一个，顺序不乱");
		batch.result(PacedBatch.Outcome.OK, 1000);
		assertEquals("b", batch.next(1000).orElseThrow());
	}

	@Test
	void tooManyBusyRetriesCountAsFailed() {
		var batch = new PacedBatch<String>(10, 2);
		batch.add("a");
		long now = 0;
		for (int i = 0; i < 3; i++) {
			assertEquals("a", batch.next(now).orElseThrow());
			batch.result(PacedBatch.Outcome.BUSY, now);
			now += 10;
		}
		assertTrue(batch.idle());
		assertEquals(new PacedBatch.Counts(1, 0, 1, 0), batch.counts());
	}

	@Test
	void failedIsNotRetried() {
		var batch = new PacedBatch<String>(10, 5);
		batch.add("a");
		batch.next(0);
		batch.result(PacedBatch.Outcome.FAILED, 0);
		assertTrue(batch.idle());
		assertEquals(new PacedBatch.Counts(1, 0, 1, 0), batch.counts());
	}

	@Test
	void cancelQueuedReportsUnprocessedButWaitsForTheOneInFlight() {
		var batch = new PacedBatch<String>(10, 5);
		batch.add("a");
		batch.add("b");
		batch.add("c");
		batch.next(0);
		assertEquals(List.of("b", "c"), batch.cancelQueued());
		assertFalse(batch.idle(), "正在发的那个还要等结果");
		batch.result(PacedBatch.Outcome.OK, 0);
		assertTrue(batch.idle());
		assertEquals(new PacedBatch.Counts(3, 1, 0, 2), batch.counts());
	}

	@Test
	void skipFailedCountsWithoutSending() {
		var batch = new PacedBatch<String>(10, 5);
		batch.skipFailed();
		assertTrue(batch.idle());
		assertEquals(new PacedBatch.Counts(1, 0, 1, 0), batch.counts());
	}

	@Test
	void resultWithoutInFlightIsRejected() {
		var batch = new PacedBatch<String>(10, 5);
		assertThrows(IllegalStateException.class, () -> batch.result(PacedBatch.Outcome.OK, 0));
	}
}
