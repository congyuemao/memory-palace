package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 服务端 → 客户端："请打开这篇笔记"，连同正文一起发过去。
 * 这样客户端不需要自己读文件，以后做多人服务器也能直接用。
 * refresh = true 表示"文件被改了，如果你还开着这篇就更新内容"，客户端没开着就忽略。
 */
public record OpenNotePayload(UUID id, String title, String body, boolean refresh) implements CustomPacketPayload {
	// 正文最多约 26 万字符，远超一篇笔记的正常长度
	public static final int MAX_BODY = 1 << 18;
	public static final int MAX_TITLE = 256;

	public static final Type<OpenNotePayload> TYPE = new Type<>(MemoryPalace.id("open_note"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenNotePayload> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, OpenNotePayload::id,
			ByteBufCodecs.stringUtf8(MAX_TITLE), OpenNotePayload::title,
			ByteBufCodecs.stringUtf8(MAX_BODY), OpenNotePayload::body,
			ByteBufCodecs.BOOL, OpenNotePayload::refresh,
			OpenNotePayload::new);

	/** 截到最多 max 个 char，且不把一个 emoji 之类的"代理对"切成两半。超长的包会直接编码失败。 */
	public static String clip(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
		return s.substring(0, end);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
