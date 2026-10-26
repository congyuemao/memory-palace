package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.util.Prediction;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /palace give &lt;笔记或文件夹&gt;   领取笔记书（文件夹 = 里面每篇各一本）
 * /palace list [文件夹]          列出笔记
 * /palace rescan                重新扫描笔记库
 */
public final class PalaceCommands {
	// 一次最多领这么多本，防止手滑把背包塞爆
	private static final int MAX_GIVE = 36;

	private PalaceCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("palace")
						.then(Commands.literal("give")
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(suggestions(), builder))
										.executes(PalaceCommands::give)))
						.then(Commands.literal("list")
								.executes(ctx -> list(ctx, ""))
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "folder")))))
						.then(Commands.literal("rescan").executes(PalaceCommands::rescan))
						.then(Commands.literal("new")
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> create(ctx, false))))
						.then(Commands.literal("newsecret")
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> create(ctx, true))))
						.then(Commands.literal("encrypt")
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(), builder))
										.executes(ctx -> crypto(ctx, true))))
						.then(Commands.literal("decrypt")
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(), builder))
										.executes(ctx -> crypto(ctx, false))))
						.then(Commands.literal("lock").executes(ctx -> {
							CryptoPayloads.sendAction(ctx.getSource().getPlayerOrException(), new UUID(0, 0), "lock", "", "");
							return 1;
						}))
						.then(Commands.literal("where")
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(), builder))
										.executes(PalaceCommands::where)))));
	}

	/**
	 * /palace encrypt|decrypt &lt;笔记&gt;：服务端没有钥匙，所以只把正文发给客户端，由客户端加密/解密后存回来。
	 */
	private static int crypto(CommandContext<CommandSourceStack> ctx, boolean encrypt) throws CommandSyntaxException {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!CryptoPayloads.allowed(player)) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_denied"));
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = vault.resolve(path);
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		if (note.get().encrypted() == encrypt) {
			ctx.getSource().sendFailure(Component.translatable(encrypt
					? "command.memorypalace.already_encrypted" : "command.memorypalace.not_encrypted", note.get().title()));
			return 0;
		}
		try {
			String body = vault.readBody(note.get().id()).orElse("");
			if ((encrypt && body.length() > CryptoPayloads.MAX_ACTION_BODY)
					|| !CryptoPayloads.sendAction(player, note.get().id(), encrypt ? "encrypt" : "decrypt", note.get().title(), body)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
				return 0;
			}
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
	}

	/**
	 * /palace new &lt;文件夹/文件名&gt;：新建笔记，给一本笔记书，并直接打开游戏内编辑器。
	 * /palace newsecret：同上，但先加密（客户端会要求解锁或第一次设置密码）。
	 */
	private static int create(CommandContext<CommandSourceStack> ctx, boolean secret) throws CommandSyntaxException {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (secret && !CryptoPayloads.allowed(player)) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_denied"));
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "path").strip();
		NoteSummary note;
		String body;
		try {
			note = vault.createNote(path, "");
			body = vault.readBody(note.id()).orElse("");
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.new_failed", e.getMessage()));
			return 0;
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("新建笔记失败：{}", path, e);
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
		player.getInventory().placeItemBackInInventory(NoteBooks.create(note), Prediction.SERVER_ONLY);
		if (secret) {
			CryptoPayloads.sendAction(player, note.id(), "encrypt_new", note.title(), body);
		} else {
			// 新建的笔记只有一行标题，一定在 MAX_BODY 以内
			ServerPlayNetworking.send(player, new EditPayloads.OpenEditor(note.id(),
					OpenNotePayload.clip(note.title(), OpenNotePayload.MAX_TITLE), body));
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.created", note.relativePath()), false);
		return 1;
	}

	/**
	 * /palace where &lt;笔记&gt;：这篇笔记挂在哪些书架/讲台上。
	 * 显示前顺手检查：书架拆了、那一格换成别的书了，就删掉这条记录（区块没加载时没法查，照常显示并标注）。
	 */
	private static int where(CommandContext<CommandSourceStack> ctx) {
		Vault vault = requireVault(ctx);
		NoteLocations locations = PalaceServer.locations();
		if (vault == null || locations == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = vault.resolve(path);
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

	private static ServerLevel findLevel(MinecraftServer server, String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	/** 那个位置现在放的还是这篇笔记的书吗？ */
	private static boolean stillThere(ServerLevel level, BlockPos pos, int slot, UUID id) {
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
	private static String teleportCommand(NoteLocations.Location loc, ServerLevel level) {
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
				.append(" · ").append(ago(loc.seenAt()));
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

	private static Component ago(long millis) {
		long minutes = Math.max(0, (System.currentTimeMillis() - millis) / 60_000);
		if (minutes < 1) return Component.translatable("command.memorypalace.ago.now");
		if (minutes < 60) return Component.translatable("command.memorypalace.ago.minutes", minutes);
		if (minutes < 60 * 24) return Component.translatable("command.memorypalace.ago.hours", minutes / 60);
		return Component.translatable("command.memorypalace.ago.days", minutes / (60 * 24));
	}

	// /palace where 的补全：只有笔记，没有文件夹
	private static List<String> noteSuggestions() {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		return vault.list().stream().map(n -> n.relativePath().substring(0, n.relativePath().length() - 3)).toList();
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "path").strip();
		ServerPlayer player = ctx.getSource().getPlayerOrException();

		Optional<NoteSummary> single = vault.resolve(path);
		List<NoteSummary> notes = single.map(List::of).orElseGet(() -> vault.listFolder(path));
		if (notes.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		List<NoteSummary> given = notes.subList(0, Math.min(notes.size(), MAX_GIVE));
		for (NoteSummary note : given) {
			// 背包满了就掉在脚下
			player.getInventory().placeItemBackInInventory(NoteBooks.create(note), Prediction.SERVER_ONLY);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.given", given.size()), false);
		if (notes.size() > given.size()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.given_partial",
					notes.size(), notes.get(given.size()).relativePath()), false);
		}
		return given.size();
	}

	private static int list(CommandContext<CommandSourceStack> ctx, String folder) {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		List<NoteSummary> notes = folder.isBlank() ? vault.list() : vault.listFolder(folder.strip());
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.list_header", notes.size()), false);
		for (NoteSummary note : notes) {
			ctx.getSource().sendSuccess(() -> Component.literal("  " + note.relativePath() + "  —  " + note.title()), false);
		}
		return notes.size();
	}

	private static int rescan(CommandContext<CommandSourceStack> ctx) {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		try {
			vault.scan();
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.rescanned", vault.list().size()), false);
		return vault.list().size();
	}

	private static Vault requireVault(CommandContext<CommandSourceStack> ctx) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.no_vault"));
		}
		return vault;
	}

	// 补全：所有笔记路径（去掉 .md）+ 所有文件夹
	private static List<String> suggestions() {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		var out = new java.util.TreeSet<String>();
		for (NoteSummary note : vault.list()) {
			String p = note.relativePath();
			out.add(p.substring(0, p.length() - 3));
			if (!note.folder().isEmpty()) {
				out.add(note.folder());
			}
		}
		return List.copyOf(out);
	}
}
