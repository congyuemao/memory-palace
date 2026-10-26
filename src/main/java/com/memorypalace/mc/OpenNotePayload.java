package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端："请打开这篇笔记"，连同正文一起发过去（正文分成若干块，客户端收齐再拼起来，绝不截断）。
 * 这样客户端不需要自己读文件，以后做多人服务器也能直接用。
 * refresh = true 表示"文件被改了，如果你还开着这篇就更新内容"，客户端没开着就忽略。
 */
public record OpenNotePayload(UUID id, String title, String part, boolean refresh, int index, int total)
		implements CustomPacketPayload {
	/** 每块最多这么多字符（中文 3 字节/字 → 约 300KB，在单个包的上限以内）。 */
	public static final int CHUNK_CHARS = 100_000;
	/** 游戏内编辑器 / 新建笔记一次能处理的最长正文。 */
	public static final int MAX_BODY = 200_000;
	public static final int MAX_TITLE = 256;

	public static final Type<OpenNotePayload> TYPE = new Type<>(MemoryPalace.id("open_note"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenNotePayload> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, OpenNotePayload::id,
			ByteBufCodecs.stringUtf8(MAX_TITLE), OpenNotePayload::title,
			ByteBufCodecs.stringUtf8(CHUNK_CHARS), OpenNotePayload::part,
			ByteBufCodecs.BOOL, OpenNotePayload::refresh,
			ByteBufCodecs.VAR_INT, OpenNotePayload::index,
			ByteBufCodecs.VAR_INT, OpenNotePayload::total,
			OpenNotePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** 把正文切成若干块（不把 emoji 之类的"代理对"切成两半）。空正文也是一块。 */
	public static List<OpenNotePayload> split(UUID id, String title, String body, boolean refresh) {
		List<String> parts = new ArrayList<>();
		int i = 0;
		do {
			int end = Math.min(body.length(), i + CHUNK_CHARS);
			if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) {
				end--;
			}
			parts.add(body.substring(i, end));
			i = end;
		} while (i < body.length());
		List<OpenNotePayload> out = new ArrayList<>();
		String t = clip(title, MAX_TITLE);
		for (int n = 0; n < parts.size(); n++) {
			out.add(new OpenNotePayload(id, t, parts.get(n), refresh, n, parts.size()));
		}
		return out;
	}

	/** 截到最多 max 个 char（只用于标题这种可以截的东西），且不把"代理对"切成两半。 */
	public static String clip(String s, int max) {
		if (s.length() <= max) {
			return s;
		}
		int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
		return s.substring(0, end);
	}
}
