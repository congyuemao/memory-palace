package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** /palace where，以及按记录的位置找书架/讲台、生成传送命令（工具界面也用）。 */
final class LocationCommands {
	private LocationCommands() {}

	/**
	 * /palace where &lt;笔记&gt;：这篇笔记挂在哪些书架/讲台上。
	 * 显示前顺手检查：书架拆了、那一格换成别的书了，就删掉这条记录（区块没加载时没法查，照常显示并标注）。
	 */
	static int where(CommandContext<CommandSourceStack> ctx) {
		Vault vault = CommandSupport.requireVault(ctx);
		NoteLocations locations = PalaceServer.locations();
		if (vault == null || locations == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		// 读不了的笔记当作不存在：不透露"有这么一篇、放在哪里"
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, ctx.getSource().getPlayer(), path).filter(n -> CommandSupport.readable(ctx.getSource(), n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		UUID id = note.get().id();
		MinecraftServer server = ctx.getSource().getServer();
		int shown = 0;
		for (NoteLocations.Location loc : locations.get(id)) {
			ServerLevel level = findLevel(server, loc.dimension());
			BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
			boolean loaded = level != null && level.isLoaded(pos);
			if (level == null || (loaded && !stillThere(level, pos, loc.slot(), id))) {
				try {
					locations.remove(id, loc);
				} catch (IOException e) {
					MemoryPalace.LOGGER.warn("删除过期位置记录失败", e);
				}
				continue;
			}
			String tp = teleportCommand(loc, loaded ? level : null);
			ctx.getSource().sendSuccess(() -> describe(loc, loaded, tp), false);
			shown++;
		}
		if (shown == 0) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.where_none", note.get().title()), false);
		} else {
			int count = shown;
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.where_header", note.get().title(), count), false);
		}
		return shown;
	}

	static ServerLevel findLevel(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	/** 那个位置现在放的还是这篇笔记的书吗？ */
	static boolean stillThere(ServerLevel level, BlockPos pos, int slot, UUID id) {
		ItemStack book = switch (level.getBlockEntity(pos)) {
			case LecternBlockEntity lectern when slot == NoteLocations.LECTERN_SLOT -> lectern.getBook();
			case ChiseledBookShelfBlockEntity shelf when slot >= 0 && slot < 6 -> shelf.getItem(slot);
			case null, default -> ItemStack.EMPTY;
		};
		return NoteBooks.idOf(book).filter(id::equals).isPresent();
	}

	// 一行：主世界 (12, 64, -30) 书架第 3 格 · 5 分钟前；点坐标会把传送命令填进聊天框
	/**
	 * 传送到书架/讲台正前方那一格，脸朝着它。正面前面一定是空的（不然没法右键它），所以不会卡进方块。
	 * level 为 null（区块没加载、读不到朝向）时退回到方块正上方。
	 */
	static String teleportCommand(NoteLocations.Location loc, ServerLevel level) {
		BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
		String prefix = "/execute in " + loc.dimension() + " run tp @s ";
		if (level != null) {
			BlockState state = level.getBlockState(pos);
			if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
				BlockPos front = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
				return prefix + (front.getX() + 0.5) + " " + front.getY() + " " + (front.getZ() + 0.5)
						+ " facing " + (pos.getX() + 0.5) + " " + (pos.getY() + 0.5) + " " + (pos.getZ() + 0.5);
			}
		}
		return prefix + (pos.getX() + 0.5) + " " + (pos.getY() + 1) + " " + (pos.getZ() + 0.5);
	}

	private static Component describe(NoteLocations.Location loc, boolean verified, String tpCommand) {
		String coords = "(" + loc.x() + ", " + loc.y() + ", " + loc.z() + ")";
		Component place = loc.slot() == NoteLocations.LECTERN_SLOT
				? Component.translatable("command.memorypalace.where_lectern")
				: Component.translatable("command.memorypalace.where_slot", loc.slot() + 1);
		var line = Component.literal("  ")
				.append(dimensionName(loc.dimension())).append(" ")
				.append(Component.literal(coords).withStyle(Style.EMPTY.withColor(0x55FFFF).withUnderlined(true)
						.withClickEvent(new ClickEvent.SuggestCommand(tpCommand))
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("command.memorypalace.where_click")))))
				.append(" ").append(place)
				.append(" · ").append(CommandSupport.ago(loc.seenAt()));
		if (!verified) {
			line.append(Component.translatable("command.memorypalace.where_unloaded").withStyle(Style.EMPTY.withColor(0xAAAAAA)));
		}
		return line;
	}

	private static Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> Component.translatable("command.memorypalace.dim.overworld");
			case "minecraft:the_nether" -> Component.translatable("command.memorypalace.dim.nether");
			case "minecraft:the_end" -> Component.translatable("command.memorypalace.dim.end");
			default -> Component.literal(dimension);
		};
	}
}
