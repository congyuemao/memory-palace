package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

import java.util.Optional;

/** 准星对准书架上的书、讲台上的书时显示书名。 */
public final class LookPayloads {
	private LookPayloads() {}

	/** 客户端 → 服务端：这个书架的这一格（讲台为 -1）是什么书？ */
	public record LookQuery(BlockPos pos, int slot) implements CustomPacketPayload {
		public static final Type<LookQuery> TYPE = new Type<>(MemoryPalace.id("look_query"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookQuery> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookQuery::pos, ByteBufCodecs.VAR_INT, LookQuery::slot, LookQuery::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：那一格的书名（"文件夹 / 标题"；不是笔记书时为 ""）。 */
	public record LookInfo(BlockPos pos, int slot, String text) implements CustomPacketPayload {
		public static final Type<LookInfo> TYPE = new Type<>(MemoryPalace.id("look_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookInfo> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookInfo::pos, ByteBufCodecs.VAR_INT, LookInfo::slot,
				ByteBufCodecs.stringUtf8(512), LookInfo::text, LookInfo::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(LookQuery.TYPE, LookQuery.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LookInfo.TYPE, LookInfo.CODEC);
		// 准星书名：谁都能用，但只看得到自己读得了的笔记（每一篇单独检查）
		ServerPlayNetworking.registerGlobalReceiver(LookQuery.TYPE, (p, ctx) -> look(ctx.player(), p));
	}

	private static void look(ServerPlayer player, LookQuery query) {
		String text = "";
		// 用原版的方块交互距离（和右键能够到的范围一致），而不是固定半径；多人关着时其他玩家什么都不显示
		if (PalaceAccess.enabled(player) && player.isWithinBlockInteractionRange(query.pos(), 1.0)) {
			ItemStack book = switch (player.level().getBlockEntity(query.pos())) {
				case ChiseledBookShelfBlockEntity shelf when query.slot() >= 0 && query.slot() < 6 -> shelf.getItem(query.slot());
				case LecternBlockEntity lectern when query.slot() == NoteLocations.LECTERN_SLOT -> lectern.getBook();
				case null, default -> ItemStack.EMPTY;
			};
			Vault vault = PalaceServer.vault();
			Optional<NoteSummary> note = vault == null ? Optional.empty() : NoteBooks.idOf(book).flatMap(vault::find);
			if (note.isPresent()) {
				text = label(note.get(), PalaceAccess.canRead(player, note.get()));
			}
		}
		ServerPlayNetworking.send(player, new LookInfo(query.pos(), query.slot(), text));
	}

	/**
	 * "文件夹 / 标题"。加密笔记不显示正文标题：作者看到"【加密】路径"；
	 * 读不了、标题也不是人人可见的笔记：和书名一样只有"【私密笔记】#7f3a"，不带路径（见 NoteBooks.titleFor）。
	 */
	static String label(NoteSummary note, boolean canRead) {
		if (!canRead && !note.encrypted() && !PalaceAccess.titleIsPublic(note)) {
			return NoteBooks.titleFor(note); // 读不了、标题也不公开：和书名一样，只有"【私密笔记】#7f3a"这种，不带路径
		}
		if (note.encrypted()) {
			String path = note.relativePath();
			return "【加密】" + path.substring(0, path.length() - 3);
		}
		return note.folder().isEmpty() ? note.title() : note.folder() + " / " + note.title();
	}
}
