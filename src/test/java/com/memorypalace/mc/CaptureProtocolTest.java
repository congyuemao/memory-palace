package com.memorypalace.mc;

import com.memorypalace.core.vault.CaptureOutbox;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 随手记的新旧版本兼容：0.3.0 的消息只有正文和本地时间，现在的多了编号和重发标记，两种布局必须用不同的标识。
 * 内容都是虚构的，不碰任何笔记或存档。
 */
class CaptureProtocolTest {
	private static final String TIME = "2026-10-30T09:15:00+08:00";
	private static final String WHERE = "remote|example.invalid";
	private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	/** 0.3.0 客户端发的字节：capture 标识下只有两个字符串（照抄 v0.3.0 的 StreamCodec.composite 布局）。 */
	private static RegistryFriendlyByteBuf legacyBytes(String text, int maxText) {
		RegistryFriendlyByteBuf buf = buffer();
		buf.writeUtf(text, maxText);
		buf.writeUtf(TIME, 64);
		return buf;
	}

	@Test
	void newLayoutsUseTheirOwnIdentifiers() {
		assertEquals("memorypalace:capture", ToolPayloads.LegacyCapture.TYPE.id().toString(), "和 0.3.0 一样");
		assertEquals("memorypalace:capture_sealed", ToolPayloads.LegacyCaptureSealed.TYPE.id().toString(), "和 0.3.0 一样");
		assertNotEquals(ToolPayloads.LegacyCapture.TYPE.id(), ToolPayloads.Capture.TYPE.id());
		assertNotEquals(ToolPayloads.LegacyCaptureSealed.TYPE.id(), ToolPayloads.CaptureSealed.TYPE.id());
	}

	@Test
	void oldClientMessagesAreDecodedWithTheOldLayout() {
		RegistryFriendlyByteBuf buf = legacyBytes("旧客户端的随手记：去找钻石", 1000);
		ToolPayloads.LegacyCapture old = ToolPayloads.LegacyCapture.CODEC.decode(buf);
		assertEquals("旧客户端的随手记：去找钻石", old.text());
		assertEquals(TIME, old.localTime());
		assertEquals(0, buf.readableBytes(), "整条读完，没有错位");

		RegistryFriendlyByteBuf sealed = legacyBytes("MPALBOX1:虚构的密文", 8192);
		assertEquals("MPALBOX1:虚构的密文", ToolPayloads.LegacyCaptureSealed.CODEC.decode(sealed).line());
		assertEquals(0, sealed.readableBytes());
	}

	@Test
	void oldBytesDoNotFitTheNewLayout() {
		// 这就是为什么不能共用一个标识：按新布局读旧消息，要么报错，要么读出乱七八糟的编号和正文
		RegistryFriendlyByteBuf buf = legacyBytes("旧客户端的随手记：去找钻石", 1000);
		try {
			ToolPayloads.Capture misread = ToolPayloads.Capture.CODEC.decode(buf);
			assertNotEquals("旧客户端的随手记：去找钻石", misread.text());
		} catch (RuntimeException expected) {
			// 解码失败也说明布局不兼容
		}
	}

	@Test
	void sameVersionRoundTripKeepsIdAndRetry() {
		RegistryFriendlyByteBuf buf = buffer();
		ToolPayloads.Capture.CODEC.encode(buf, new ToolPayloads.Capture(ID, true, "新客户端的随手记", TIME));
		assertEquals(new ToolPayloads.Capture(ID, true, "新客户端的随手记", TIME), ToolPayloads.Capture.CODEC.decode(buf));

		RegistryFriendlyByteBuf sealed = buffer();
		ToolPayloads.CaptureSealed.CODEC.encode(sealed, new ToolPayloads.CaptureSealed(ID, false, "MPALBOX1:虚构的密文", TIME));
		assertEquals(new ToolPayloads.CaptureSealed(ID, false, "MPALBOX1:虚构的密文", TIME), ToolPayloads.CaptureSealed.CODEC.decode(sealed));

		RegistryFriendlyByteBuf result = buffer();
		ToolPayloads.CaptureResult.CODEC.encode(result, new ToolPayloads.CaptureResult(ID, "OK", "收件箱/2026-10-30.md"));
		assertEquals(new ToolPayloads.CaptureResult(ID, "OK", "收件箱/2026-10-30.md"), ToolPayloads.CaptureResult.CODEC.decode(result));
	}

	@Test
	void onlyServersThatKnowTheNewLayoutAreSentTo() {
		Set<CustomPacketPayload.Type<?>> oldServer = Set.of(ToolPayloads.LegacyCapture.TYPE, ToolPayloads.LegacyCaptureSealed.TYPE);
		Set<CustomPacketPayload.Type<?>> newServer = Set.of(ToolPayloads.Capture.TYPE, ToolPayloads.CaptureSealed.TYPE,
				ToolPayloads.LegacyCapture.TYPE, ToolPayloads.LegacyCaptureSealed.TYPE);
		assertFalse(ToolPayloads.captureSupported(oldServer::contains), "0.3.0 的服务端");
		assertFalse(ToolPayloads.captureSupported(Set.<CustomPacketPayload.Type<?>>of()::contains), "没装模组的服务端");
		assertTrue(ToolPayloads.captureSupported(newServer::contains), "同版本");
	}

	@Test
	void textStaysRecoverableWhenTheServerIsTooOld() {
		// 新客户端连 0.3.0 服务端：InboxCapture 在发送前就 fail，什么都没发
		CaptureOutbox outbox = new CaptureOutbox();
		OffsetDateTime t = OffsetDateTime.of(2026, 10, 30, 9, 15, 0, 0, ZoneOffset.ofHours(8));
		CaptureOutbox.Draft draft = outbox.begin(null, "服务端升级前写的随手记", t, WHERE, true, 0);
		outbox.fail(draft);
		assertSame(draft, outbox.nextFailed(WHERE));
		assertEquals("服务端升级前写的随手记", draft.restoreText());

		// 服务端升级后找回：同一条、同一个编号；之前没发过，所以不算重发，照常加密
		CaptureOutbox.Draft again = outbox.begin(draft, "服务端升级前写的随手记", t.plusHours(1), WHERE, true, 1_000);
		assertSame(draft, again);
		assertFalse(again.prepared(), "要求加密：重新用收件箱公钥加密，不会退回明文");
		outbox.prepare(again, "MPALBOX1:虚构的密文");
		assertFalse(outbox.sending(again, 1_000));
		assertEquals(t, again.time(), "仍记进写下它的那一天");
		assertNotNull(outbox.onResult(again.id(), true));
		assertEquals(0, outbox.size());
	}
}
