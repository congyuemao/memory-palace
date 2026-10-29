package com.memorypalace.mc;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 随手记消息编码后再解码，编号、重发标记、正文和时间都不变。内容都是虚构的。 */
class CapturePayloadsTest {
	private static final String TIME = "2026-10-30T09:15:00+08:00";
	private static final UUID ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void roundTripKeepsIdAndRetry() {
		RegistryFriendlyByteBuf buf = buffer();
		ToolPayloads.Capture.CODEC.encode(buf, new ToolPayloads.Capture(ID, true, "去找钻石", TIME));
		assertEquals(new ToolPayloads.Capture(ID, true, "去找钻石", TIME), ToolPayloads.Capture.CODEC.decode(buf));

		RegistryFriendlyByteBuf sealed = buffer();
		ToolPayloads.CaptureSealed.CODEC.encode(sealed, new ToolPayloads.CaptureSealed(ID, false, "MPALBOX1:虚构的密文", TIME));
		assertEquals(new ToolPayloads.CaptureSealed(ID, false, "MPALBOX1:虚构的密文", TIME), ToolPayloads.CaptureSealed.CODEC.decode(sealed));

		RegistryFriendlyByteBuf result = buffer();
		ToolPayloads.CaptureResult.CODEC.encode(result, new ToolPayloads.CaptureResult(ID, "OK", "收件箱/2026-10-30.md"));
		assertEquals(new ToolPayloads.CaptureResult(ID, "OK", "收件箱/2026-10-30.md"), ToolPayloads.CaptureResult.CODEC.decode(result));
	}
}
