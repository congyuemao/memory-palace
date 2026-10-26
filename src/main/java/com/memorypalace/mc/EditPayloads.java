package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏内编辑器保存笔记：客户端把正文分块发给服务端（客户端→服务端的包上限只有约 32KB），
 * 服务端收齐后写文件，回一个结果。
 */
public final class EditPayloads {
	/** 每块最多这么多字符：中文 3 字节/字，8000 字 ≈ 24KB，在 32KB 上限以内。 */
	public static final int CHUNK_CHARS = 8000;
	private static final int MAX_CHUNKS = 64;

	private EditPayloads() {}

	/**
	 * 客户端 → 服务端：正文的第 index 块（共 total 块）。
	 * expectedHash 是编辑器打开时旧正文的指纹，"" 表示强制覆盖。session 区分同一个玩家的不同次保存。
	 */
	public record SaveChunk(UUID noteId, int session, int index, int total, String expectedHash, String part)
			implements CustomPacketPayload {
		public static final Type<SaveChunk> TYPE = new Type<>(MemoryPalace.id("save_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveChunk> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SaveChunk::noteId,
				ByteBufCodecs.VAR_INT, SaveChunk::session,
				ByteBufCodecs.VAR_INT, SaveChunk::index,
				ByteBufCodecs.VAR_INT, SaveChunk::total,
				ByteBufCodecs.stringUtf8(64), SaveChunk::expectedHash,
				ByteBufCodecs.stringUtf8(CHUNK_CHARS), SaveChunk::part,
				SaveChunk::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：保存结果。result 是 Vault.WriteResult 的名字，或 "ERROR"。newHash 是保存后正文的指纹。 */
	public record SaveResult(UUID noteId, int session, String result, String newHash) implements CustomPacketPayload {
		public static final Type<SaveResult> TYPE = new Type<>(MemoryPalace.id("save_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveResult> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SaveResult::noteId,
				ByteBufCodecs.VAR_INT, SaveResult::session,
				ByteBufCodecs.stringUtf8(32), SaveResult::result,
				ByteBufCodecs.stringUtf8(64), SaveResult::newHash,
				SaveResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：直接打开游戏内编辑器（/palace new 新建笔记之后）。 */
	public record OpenEditor(UUID noteId, String title, String body) implements CustomPacketPayload {
		public static final Type<OpenEditor> TYPE = new Type<>(MemoryPalace.id("open_editor"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenEditor> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, OpenEditor::noteId,
				ByteBufCodecs.stringUtf8(OpenNotePayload.MAX_TITLE), OpenEditor::title,
				ByteBufCodecs.stringUtf8(OpenNotePayload.MAX_BODY), OpenEditor::body,
				OpenEditor::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// 正在接收的保存：玩家 UUID → 已收到的块
	private record Assembly(UUID noteId, int session, String expectedHash, String[] parts) {}

	private static final Map<UUID, Assembly> PENDING = new ConcurrentHashMap<>();

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(SaveChunk.TYPE, SaveChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SaveResult.TYPE, SaveResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenEditor.TYPE, OpenEditor.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SaveChunk.TYPE, (chunk, context) -> receive(context.player(), chunk));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
	}

	private static void receive(ServerPlayer player, SaveChunk chunk) {
		if (chunk.total() < 1 || chunk.total() > MAX_CHUNKS || chunk.index() < 0 || chunk.index() >= chunk.total()) {
			reply(player, chunk, "ERROR", "");
			return;
		}
		Assembly a = PENDING.get(player.getUUID());
		// 新的一次保存（或者上次没发完就又保存了）：重新开始收
		if (a == null || a.session() != chunk.session() || !a.noteId().equals(chunk.noteId()) || a.parts().length != chunk.total()) {
			a = new Assembly(chunk.noteId(), chunk.session(), chunk.expectedHash(), new String[chunk.total()]);
			PENDING.put(player.getUUID(), a);
		}
		a.parts()[chunk.index()] = chunk.part();
		for (String part : a.parts()) {
			if (part == null) {
				return; // 还没收齐
			}
		}
		PENDING.remove(player.getUUID());
		String body = String.join("", a.parts());

		Vault vault = PalaceServer.vault();
		if (vault == null) {
			reply(player, chunk, "ERROR", "");
			return;
		}
		try {
			Vault.WriteResult result = vault.writeBody(chunk.noteId(), body,
					a.expectedHash().isEmpty() ? null : a.expectedHash());
			reply(player, chunk, result.name(), result == Vault.WriteResult.OK ? Vault.hash(body) : "");
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.error("保存笔记 {} 失败", chunk.noteId(), e);
			reply(player, chunk, "ERROR", "");
		}
	}

	private static void reply(ServerPlayer player, SaveChunk chunk, String result, String newHash) {
		ServerPlayNetworking.send(player, new SaveResult(chunk.noteId(), chunk.session(), result, newHash));
	}
}
