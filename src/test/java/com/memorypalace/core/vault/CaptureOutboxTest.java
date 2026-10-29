package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class CaptureOutboxTest {
	private static final OffsetDateTime T = OffsetDateTime.of(2026, 10, 29, 9, 15, 0, 0, ZoneOffset.ofHours(8));
	private static final OffsetDateTime LATER = T.plusMinutes(3);
	private static final String HERE = "local|E:/fake/saves/测试世界";
	private static final String ELSEWHERE = "remote|example.invalid";

	private final CaptureOutbox outbox = new CaptureOutbox();

	private CaptureOutbox.Draft sendPlain(String text) {
		CaptureOutbox.Draft draft = outbox.begin(null, text, T, HERE, false, 0);
		outbox.prepare(draft, null);
		assertFalse(outbox.sending(draft, 0));
		return draft;
	}

	@Test
	void savedReceiptRemovesTheDraft() {
		CaptureOutbox.Draft draft = sendPlain("买火把");
		var receipt = outbox.onResult(draft.id(), true);
		assertSame(draft, receipt.draft());
		assertFalse(receipt.late());
		assertEquals(CaptureOutbox.State.SAVED, draft.state());
		assertEquals(0, outbox.size());
		assertNull(outbox.nextFailed(HERE));
	}

	@Test
	void serverRejectionKeepsTheTextForRecovery() {
		CaptureOutbox.Draft draft = sendPlain("矿洞在 120, -40");
		outbox.onResult(draft.id(), false);
		CaptureOutbox.Draft restored = outbox.nextFailed(HERE);
		assertSame(draft, restored);
		assertEquals("矿洞在 120, -40", restored.restoreText());
	}

	@Test
	void keyringFailureBeforeSendingKeepsTheText() {
		CaptureOutbox.Draft draft = outbox.begin(null, "加密的想法", T, HERE, true, 0);
		assertFalse(draft.prepared());
		outbox.fail(draft); // 钥匙圈读取失败：什么都没发
		assertSame(draft, outbox.nextFailed(HERE));

		// 找回、原文没改：同一条（没发过，所以不算重发）；还没准备好，要重新读钥匙圈
		CaptureOutbox.Draft again = outbox.begin(draft, "加密的想法", LATER, HERE, true, 1_000);
		assertSame(draft, again);
		assertFalse(again.prepared());
		assertEquals(T, again.time());
		outbox.prepare(again, "MPALBOX1:fake");
		assertFalse(outbox.sending(again, 1_000));
	}

	@Test
	void timeoutThenRetryReusesTheSameIdAndCiphertext() {
		CaptureOutbox.Draft draft = outbox.begin(null, "秘密", T, HERE, true, 0);
		int attempt = draft.attempt();
		outbox.prepare(draft, "MPALBOX1:same-bytes");
		outbox.sending(draft, 0);

		assertTrue(outbox.expire(CaptureOutbox.TIMEOUT_MS - 1).isEmpty());
		assertEquals(1, outbox.expire(CaptureOutbox.TIMEOUT_MS).size());
		assertEquals(CaptureOutbox.State.FAILED, draft.state());

		CaptureOutbox.Draft again = outbox.begin(outbox.nextFailed(HERE), "秘密", LATER, HERE, true, 20_000);
		assertSame(draft, again);
		assertNotEquals(attempt, again.attempt(), "迟到的钥匙圈、确认框结果要能认出是旧的一次");
		assertTrue(again.prepared());
		assertEquals("MPALBOX1:same-bytes", again.sealedLine(), "重发同一行密文，不重新加密");
		assertEquals(T, again.time());
		assertTrue(outbox.sending(again, 20_000), "同一个编号第二次发：标成重发");

		// 第一次的结果迟到了：一样认
		assertFalse(outbox.onResult(draft.id(), true).late());
		assertEquals(0, outbox.size());
	}

	@Test
	void lateSuccessAfterTimeoutIsMarkedLateAndRemoved() {
		CaptureOutbox.Draft draft = sendPlain("迟到");
		outbox.expire(CaptureOutbox.TIMEOUT_MS);
		var receipt = outbox.onResult(draft.id(), true);
		assertTrue(receipt.late());
		assertNull(outbox.nextFailed(HERE));
		// 找回界面还开着、原文没改就回车：已经保存过了，不再发
		assertNull(outbox.begin(draft, "迟到", LATER, HERE, false, 30_000));
		assertEquals(0, outbox.size());
	}

	@Test
	void editedTextBecomesANewCapture() {
		CaptureOutbox.Draft draft = sendPlain("第一版");
		outbox.onResult(draft.id(), false);
		CaptureOutbox.Draft edited = outbox.begin(draft, "第二版", LATER, HERE, false, 0);
		assertNotSame(draft, edited);
		assertNotEquals(draft.id(), edited.id());
		assertEquals(LATER, edited.time());
		assertEquals(1, outbox.size(), "旧的那条被新的取代");
	}

	@Test
	void anotherWorldGetsAFreshCaptureEncryptedForThatWorld() {
		CaptureOutbox.Draft draft = outbox.begin(null, "换世界", T, HERE, true, 0);
		outbox.prepare(draft, "MPALBOX1:here-key");
		outbox.sending(draft, 0);
		outbox.disconnect();
		CaptureOutbox.Draft there = outbox.begin(outbox.nextFailed(HERE), "换世界", LATER, ELSEWHERE, true, 0);
		assertNotEquals(draft.id(), there.id());
		assertFalse(there.prepared());
	}

	@Test
	void plainPayloadIsNeverReusedOnceEncryptionIsRequired() {
		CaptureOutbox.Draft draft = sendPlain("先明文");
		outbox.onResult(draft.id(), false);
		CaptureOutbox.Draft again = outbox.begin(draft, "先明文", LATER, HERE, true, 0);
		assertNotEquals(draft.id(), again.id());
		assertFalse(again.prepared(), "要重新读钥匙圈加密，不拿上次的明文重发");
	}

	@Test
	void sealedPayloadIsReusedEvenIfEncryptionWasTurnedOff() {
		CaptureOutbox.Draft draft = outbox.begin(null, "密文", T, HERE, true, 0);
		outbox.prepare(draft, "MPALBOX1:x");
		outbox.sending(draft, 0);
		outbox.onResult(draft.id(), false);
		CaptureOutbox.Draft again = outbox.begin(draft, "密文", LATER, HERE, false, 0);
		assertSame(draft, again);
		assertEquals("MPALBOX1:x", again.sealedLine());
	}

	@Test
	void plainChosenBecauseNoKeyringIsPreparedAgainOnRetry() {
		CaptureOutbox.Draft draft = outbox.begin(null, "没钥匙圈", T, HERE, true, 0);
		outbox.prepare(draft, null); // 当时还没设密码，只能发明文
		outbox.sending(draft, 0);
		outbox.onResult(draft.id(), false);
		CaptureOutbox.Draft again = outbox.begin(draft, "没钥匙圈", LATER, HERE, true, 0);
		assertFalse(again.prepared(), "现在可能已经有钥匙圈了：重新读一次再决定，不沿用明文");
	}

	@Test
	void draftsFromAnotherWorldAreNotOfferedHere() {
		CaptureOutbox.Draft draft = sendPlain("服务器 A 的想法");
		outbox.disconnect();
		assertNull(outbox.nextFailed(ELSEWHERE), "不会把 A 的随手记拿到 B 去找回");
		assertEquals(0, outbox.failedCount(ELSEWHERE));
		assertSame(draft, outbox.nextFailed(HERE), "回到 A 还能找回");
		assertEquals(1, outbox.failedCount(HERE));
	}

	@Test
	void confirmDialogIsNotTimedOut() {
		CaptureOutbox.Draft draft = outbox.begin(null, "等确认", T, HERE, true, 0);
		outbox.confirming(draft);
		assertTrue(outbox.expire(10 * CaptureOutbox.TIMEOUT_MS).isEmpty());
		assertEquals(CaptureOutbox.State.CONFIRMING, draft.state());
	}

	@Test
	void disconnectFailsEverythingInFlightButKeepsIt() {
		CaptureOutbox.Draft sent = sendPlain("一");
		CaptureOutbox.Draft preparing = outbox.begin(null, "二", T, HERE, true, 0);
		outbox.disconnect();
		assertEquals(CaptureOutbox.State.FAILED, sent.state());
		assertEquals(CaptureOutbox.State.FAILED, preparing.state());
		assertSame(sent, outbox.nextFailed(HERE), "按先后顺序找回");
		outbox.discard(sent);
		assertSame(preparing, outbox.nextFailed(HERE));
	}

	@Test
	void escKeepsEditsForTheNextRecovery() {
		CaptureOutbox.Draft draft = sendPlain("原文");
		outbox.onResult(draft.id(), false);
		outbox.keepEdit(draft, "原文加一点");
		assertEquals("原文加一点", outbox.nextFailed(HERE).restoreText());
		assertEquals("原文", draft.text());
		outbox.keepEdit(draft, "原文");
		assertEquals("原文", draft.restoreText());
	}

	@Test
	void unknownReceiptIsIgnored() {
		sendPlain("x");
		assertNull(outbox.onResult(java.util.UUID.randomUUID(), true));
		assertEquals(1, outbox.size());
	}
}
