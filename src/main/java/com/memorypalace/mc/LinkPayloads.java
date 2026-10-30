package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLinks;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 笔记之间的链接：玩家在书页上点了一个链接（[[笔记名]] 或 [文字](路径.md)），服务端找到对应的笔记打开。
 * 只在读得了的笔记里找：读不了的和不存在的一样，都说找不到（不透露"有这么一篇"）。
 */
public final class LinkPayloads {
	public static final int MAX_LINK = 512;

	private LinkPayloads() {}

	/** 客户端 → 服务端：在 source 这篇笔记里点了 link（MdSpan.link 的原样）。 */
	public record OpenLink(UUID source, String link) implements CustomPacketPayload {
		public static final Type<OpenLink> TYPE = new Type<>(MemoryPalace.id("open_link"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenLink> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, OpenLink::source,
				ByteBufCodecs.stringUtf8(MAX_LINK), OpenLink::link,
				OpenLink::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(OpenLink.TYPE, OpenLink.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenLink.TYPE, (p, ctx) -> open(ctx.player(), p));
	}

	private static void open(ServerPlayer player, OpenLink request) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player)) {
			return;
		}
		// 链接所在的那篇也要读得了：不然随便填个 ID 就能拿它当跳板试探别的笔记
		Optional<NoteSummary> source = vault.find(request.source()).filter(n -> PalaceAccess.canRead(player, n));
		if (source.isEmpty()) {
			return;
		}
		List<NoteSummary> readable = vault.list().stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
		Optional<NoteSummary> target = NoteLinks.resolve(readable, source.get().relativePath(), request.link());
		if (target.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.link_not_found", shown(request.link())));
			return;
		}
		PalaceInteractions.openNote(player, target.get().id());
	}

	/** 给玩家看的链接目标：去掉 "wiki:"。 */
	static String shown(String link) {
		String text = link.startsWith(NoteLinks.WIKI) ? link.substring(NoteLinks.WIKI.length()) : link;
		return text.length() > 64 ? text.substring(0, 63) + "…" : text;
	}
}
