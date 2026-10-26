package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * 笔记里的图片：客户端问"这篇笔记里的 src 这张图"，服务端读文件、缩小、编码成 PNG 发回去。
 * 和笔记正文一样走网络，以后多人服务器也能用。
 */
public final class ImagePayloads {
	// 一个包最多约 1MB，留点余量
	private static final int MAX_BYTES = 900 * 1024;
	private static final int MAX_DIM = 1024;

	private ImagePayloads() {}

	/** 客户端 → 服务端：请求图片。 */
	public record Request(UUID noteId, String src) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(MemoryPalace.id("image_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Request::noteId,
				ByteBufCodecs.stringUtf8(1024), Request::src,
				Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：图片数据（PNG）。png 为空数组表示找不到或读不了。 */
	public record Data(UUID noteId, String src, byte[] png) implements CustomPacketPayload {
		public static final Type<Data> TYPE = new Type<>(MemoryPalace.id("image_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Data::noteId,
				ByteBufCodecs.stringUtf8(1024), Data::src,
				ByteBufCodecs.byteArray(MAX_BYTES), Data::png,
				Data::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Data.TYPE, Data.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Vault vault = PalaceServer.vault();
			Optional<NoteSummary> note = vault == null || !PalaceAccess.allowed(player)
					? Optional.empty() : vault.find(payload.noteId());
			if (note.isEmpty()) {
				send(player, payload, new byte[0]);
				return;
			}
			Path root = vault.root();
			String notePath = note.get().relativePath();
			// 读图、缩放、编码可能要几十毫秒，放到后台线程，做完回到服务端线程发送
			Thread.ofVirtual().name("memorypalace-image").start(() -> {
				byte[] png = loadPng(root, notePath, payload.src());
				player.level().getServer().execute(() -> send(player, payload, png));
			});
		});
	}

	private static void send(ServerPlayer player, Request request, byte[] png) {
		if (!player.hasDisconnected()) {
			ServerPlayNetworking.send(player, new Data(request.noteId(), request.src(), png));
		}
	}

	private static byte[] loadPng(Path root, String notePath, String src) {
		try {
			Optional<Path> file = NoteImages.resolve(root, notePath, src);
			if (file.isEmpty()) {
				return new byte[0];
			}
			// 照片编码成 PNG 可能超过一个包的上限：超了就缩小一点再试
			for (int dim = MAX_DIM; dim >= 64; dim = dim * 3 / 4) {
				Optional<Raster> raster = NoteImages.load(file.get(), dim);
				if (raster.isEmpty()) {
					return new byte[0];
				}
				byte[] png = NoteImages.toPng(raster.get());
				if (png.length <= MAX_BYTES) {
					return png;
				}
			}
			return new byte[0];
		} catch (RuntimeException e) {
			MemoryPalace.LOGGER.warn("读取笔记图片失败：{}", src, e);
			return new byte[0];
		}
	}
}
