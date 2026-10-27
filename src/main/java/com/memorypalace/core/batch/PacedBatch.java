package com.memorypalace.core.batch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 一批要逐个发给服务端的请求（比如整个文件夹逐篇加密后保存）：一次只发一个，等结果回来再发下一个；
 * 服务端说"太频繁"（BUSY，保存限速）就隔 retryDelayMs 再发同一个，最多重试 maxRetries 次。
 * 这样不用放宽服务端的限速，批量操作也能全部做完。只管顺序和计数，不认识网络、界面，可以单独测试。
 * 不是线程安全的：只在一个线程（客户端渲染线程）里用。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class PacedBatch<T> {
	/** 一次请求的结果。 */
	public enum Outcome {
		/** 成功。 */
		OK,
		/** 服务端限速：稍后重试同一个。 */
		BUSY,
		/** 失败（冲突、没权限、出错）：不重试，不覆盖。 */
		FAILED
	}

	/** 计数。unprocessed = 加进来但没成功也没失败的（还在排队、正在发，或者被取消了）。 */
	public record Counts(int total, int done, int failed, int unprocessed) {}

	private record Job<T>(T item, int retries) {}

	private final long retryDelayMs;
	private final int maxRetries;
	private final Deque<Job<T>> queue = new ArrayDeque<>();
	private Job<T> inFlight;
	private long notBefore = Long.MIN_VALUE;
	private int total;
	private int done;
	private int failed;

	public PacedBatch(long retryDelayMs, int maxRetries) {
		this.retryDelayMs = retryDelayMs;
		this.maxRetries = maxRetries;
	}

	public void add(T item) {
		queue.addLast(new Job<>(item, 0));
		total++;
	}

	/** 现在可以发下一个吗？可以就返回它（标记为"正在发"，要等 result 才会给下一个）；不行返回 empty。 */
	public Optional<T> next(long nowMs) {
		if (inFlight != null || queue.isEmpty() || nowMs < notBefore) {
			return Optional.empty();
		}
		inFlight = queue.pollFirst();
		return Optional.of(inFlight.item());
	}

	/** 正在发的那个有结果了。没有正在发的 → IllegalStateException。 */
	public void result(Outcome outcome, long nowMs) {
		if (inFlight == null) {
			throw new IllegalStateException("没有正在发的请求");
		}
		Job<T> job = inFlight;
		inFlight = null;
		switch (outcome) {
			case OK -> done++;
			case FAILED -> failed++;
			case BUSY -> {
				if (job.retries() >= maxRetries) {
					failed++;
				} else {
					// 放回队首，隔一会儿再发同一个
					queue.addFirst(new Job<>(job.item(), job.retries() + 1));
					notBefore = nowMs + retryDelayMs;
				}
			}
		}
	}

	/** 已经被跳过、算作失败的（比如解不开，根本没发）。 */
	public void skipFailed() {
		total++;
		failed++;
	}

	/** 什么都不剩了：没有排队的，也没有正在发的。 */
	public boolean idle() {
		return inFlight == null && queue.isEmpty();
	}

	/** 取消还没发的（比如上锁了：排着队的明文不能留在内存里）。返回被取消的，算作未处理；正在发的那个照常等结果。 */
	public List<T> cancelQueued() {
		List<T> cancelled = new ArrayList<>();
		for (Job<T> job : queue) {
			cancelled.add(job.item());
		}
		queue.clear();
		return cancelled;
	}

	public Counts counts() {
		return new Counts(total, done, failed, total - done - failed);
	}
}
