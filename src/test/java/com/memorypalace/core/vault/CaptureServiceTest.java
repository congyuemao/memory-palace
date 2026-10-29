package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.InboxCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 随手记从客户端待发队列到服务端写入的整条路：保存结果、失败后找回、重发去重。全部是虚构数据和临时文件夹。 */
class CaptureServiceTest {
	@TempDir
	Path dir;

	private static final OffsetDateTime T = OffsetDateTime.of(2026, 10, 29, 21, 5, 0, 0, ZoneOffset.ofHours(8));
	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final String HERE = "remote|friends.invalid";

	private Vault vault;
	private CaptureService service;
	private long clock = 1_000_000;

	@BeforeEach
	void open() throws IOException {
		vault = new Vault(dir);
		vault.scan();
		service = new CaptureService();
	}

	private CaptureService.Result plain(UUID owner, UUID request, boolean retry, String text) {
		clock += 1_000;
		return service.capture(vault, owner, owner, request, retry, T, 0, clock,
				(v, o, time, r) -> Inbox.append(v, o, time, text, r));
	}

	private CaptureService.Result sealed(UUID owner, UUID request, boolean retry, String line) {
		clock += 1_000;
		return service.capture(vault, owner, owner, request, retry, T, 0, clock,
				(v, o, time, r) -> Inbox.appendSealed(v, o, time, line, r));
	}

	private String inbox(UUID owner) throws IOException {
		return Files.readString(dir.resolve(Inbox.pathFor(owner, T)), StandardCharsets.UTF_8);
	}

	private static long count(String haystack, String needle) {
		return haystack.lines().filter(l -> l.contains(needle)).count();
	}

	@Test
	void successfulCaptureReportsThePath() throws IOException {
		var result = plain(ALICE, UUID.randomUUID(), false, "在村庄东边建农场");
		assertEquals(CaptureService.Status.OK, result.status());
		assertEquals(Inbox.pathFor(ALICE, T), result.detail());
		assertEquals(1, count(inbox(ALICE), "在村庄东边建农场"));
	}

	@Test
	void sameRequestTwiceIsAppendedOnce() throws IOException {
		UUID request = UUID.randomUUID();
		assertEquals(CaptureService.Status.OK, plain(ALICE, request, false, "只记一次").status());
		// 回执丢了、客户端重发同一个编号
		var again = plain(ALICE, request, true, "只记一次");
		assertEquals(CaptureService.Status.OK, again.status());
		assertEquals(Inbox.pathFor(ALICE, T), again.detail());
		assertEquals(1, count(inbox(ALICE), "只记一次"));
	}

	@Test
	void retryAfterServerRestartIsRecognisedByContent() throws IOException {
		UUID request = UUID.randomUUID();
		plain(ALICE, request, false, "重启前写进去了");
		String line = sealedLine("加密的一条");
		UUID sealedRequest = UUID.randomUUID();
		sealed(ALICE, sealedRequest, false, line);

		service = new CaptureService(); // 服务器重启：内存里的编号记录没了
		assertEquals(CaptureService.Status.OK, plain(ALICE, request, true, "重启前写进去了").status());
		assertEquals(CaptureService.Status.OK, sealed(ALICE, sealedRequest, true, line).status());
		String body = inbox(ALICE);
		assertEquals(1, count(body, "重启前写进去了"));
		assertEquals(1, count(body, line));
	}

	@Test
	void retryOfACaptureThatNeverArrivedIsWritten() throws IOException {
		plain(ALICE, UUID.randomUUID(), false, "别的内容");
		assertEquals(CaptureService.Status.OK, plain(ALICE, UUID.randomUUID(), true, "第一次没送到").status());
		assertEquals(1, count(inbox(ALICE), "第一次没送到"));
	}

	@Test
	void twoSeparateCapturesWithTheSameTextAreBothKept() throws IOException {
		plain(ALICE, UUID.randomUUID(), false, "喝水");
		plain(ALICE, UUID.randomUUID(), false, "喝水");
		assertEquals(2, count(inbox(ALICE), "喝水"), "不是重发就不去重：同一分钟记两次同样的话是正常的");
	}

	@Test
	void tooFastIsRejectedWithAResult() {
		UUID first = UUID.randomUUID();
		var ok = service.capture(vault, ALICE, ALICE, first, false, T, 0, 5_000,
				(v, o, time, r) -> Inbox.append(v, o, time, "一", r));
		var busy = service.capture(vault, ALICE, ALICE, UUID.randomUUID(), false, T, 0, 5_000 + CaptureService.INTERVAL_MS - 1,
				(v, o, time, r) -> Inbox.append(v, o, time, "二", r));
		assertEquals(CaptureService.Status.OK, ok.status());
		assertEquals(CaptureService.Status.BUSY, busy.status());
	}

	@Test
	void quotaNotYoursAndInvalidAllProduceResults() throws IOException {
		assertEquals(CaptureService.Status.OK, plain(ALICE, UUID.randomUUID(), false, "占一篇").status());
		// 上限 1 篇：今天这篇还能追加，换一天新建一篇就超了
		clock += 1_000;
		assertEquals(CaptureService.Status.OK, service.capture(vault, ALICE, ALICE, UUID.randomUUID(), false, T, 1, clock,
				(v, o, time, r) -> Inbox.append(v, o, time, "追加", r)).status());
		clock += 1_000;
		var tomorrow = service.capture(vault, ALICE, ALICE, UUID.randomUUID(), false, T.plusDays(1), 1, clock,
				(v, o, time, r) -> {
					throw new AssertionError("超过篇数上限不应写入");
				});
		assertEquals(CaptureService.Status.QUOTA, tomorrow.status());
		assertEquals("1", tomorrow.detail());

		Path bobs = dir.resolve(Inbox.pathFor(BOB, T));
		Files.createDirectories(bobs.getParent());
		Files.writeString(bobs, "---\npalace-id: " + UUID.randomUUID() + "\npalace-owner: " + ALICE + "\n---\n# 不是 Bob 的\n",
				StandardCharsets.UTF_8);
		vault.scan();
		clock += 1_000;
		assertEquals(CaptureService.Status.NOT_YOURS, service.capture(vault, BOB, BOB, UUID.randomUUID(), false, T, 0, clock,
				(v, o, time, r) -> Inbox.append(v, o, time, "x", r)).status());

		assertEquals(CaptureService.Status.INVALID, sealed(ALICE, UUID.randomUUID(), false, "明文冒充密文").status());
	}

	@Test
	void writeErrorIsReportedAndNotRemembered() throws IOException {
		UUID request = UUID.randomUUID();
		clock += 1_000;
		var failed = service.capture(vault, ALICE, ALICE, request, false, T, 0, clock,
				(v, o, time, r) -> {
					throw new IOException("磁盘写不进去（虚构）");
				});
		assertEquals(CaptureService.Status.FAILED, failed.status());
		assertNotNull(failed.error());
		// 同一个编号重试：上次没写进去，这次要真的写
		assertEquals(CaptureService.Status.OK, plain(ALICE, request, true, "重试成功").status());
		assertEquals(1, count(inbox(ALICE), "重试成功"));
	}

	// ---- 和客户端待发队列连起来 ----

	@Test
	void fullInboxThenRecoverAndRetry() throws IOException {
		CaptureOutbox outbox = new CaptureOutbox();
		NoteSummary note = Inbox.append(vault, ALICE, T, "先有一篇");
		vault.writeBody(note.id(), "x".repeat(Inbox.MAX_CHARS), null);

		CaptureOutbox.Draft draft = outbox.begin(null, "满了也别丢", T, HERE, false, 0);
		outbox.prepare(draft, null);
		var result = plain(ALICE, draft.id(), outbox.sending(draft, 0), draft.text());
		assertEquals(CaptureService.Status.FULL, result.status());
		outbox.onResult(draft.id(), false);

		CaptureOutbox.Draft restored = outbox.nextFailed(HERE);
		assertEquals("满了也别丢", restored.restoreText(), "按 K 找回原文");

		vault.writeBody(note.id(), "# 整理过了\n", null); // 玩家整理了收件箱
		CaptureOutbox.Draft again = outbox.begin(restored, restored.restoreText(), T.plusMinutes(5), HERE, false, 1_000);
		var retried = plain(ALICE, again.id(), outbox.sending(again, 1_000), again.text());
		assertEquals(CaptureService.Status.OK, retried.status());
		assertNotNull(outbox.onResult(again.id(), true));
		assertEquals(0, outbox.size());
		assertEquals(1, count(inbox(ALICE), "满了也别丢"));
		assertTrue(inbox(ALICE).contains("- 21:05 "), "重试用第一次按回车的时刻");
	}

	@Test
	void lostReceiptThenTimeoutThenRetryDoesNotDuplicate() throws IOException {
		CaptureOutbox outbox = new CaptureOutbox();
		CaptureOutbox.Draft draft = outbox.begin(null, "回执丢了", T, HERE, true, 0);
		outbox.prepare(draft, sealedLine("回执丢了"));
		var first = sealed(ALICE, draft.id(), outbox.sending(draft, 0), draft.sealedLine());
		assertEquals(CaptureService.Status.OK, first.status()); // 写进去了，但结果没传回客户端

		assertEquals(1, outbox.expire(CaptureOutbox.TIMEOUT_MS).size());
		service = new CaptureService(); // 顺便模拟服务器重启
		CaptureOutbox.Draft again = outbox.begin(outbox.nextFailed(HERE), "回执丢了", T.plusMinutes(1), HERE, true, 20_000);
		assertSame(draft, again);
		boolean retry = outbox.sending(again, 20_000);
		assertTrue(retry);
		assertEquals(CaptureService.Status.OK, sealed(ALICE, again.id(), retry, again.sealedLine()).status());
		assertFalse(outbox.onResult(again.id(), true).late());
		assertEquals(1, count(inbox(ALICE), draft.sealedLine()));
	}

	private static String sealedLine(String plaintext) {
		var keys = InboxCipher.generate();
		try {
			return InboxCipher.seal(keys.publicKey(), Inbox.entryLine(T, plaintext));
		} finally {
			Arrays.fill(keys.privateKey(), (byte) 0);
		}
	}
}
