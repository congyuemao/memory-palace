package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteAccess;
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

import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.players.NameAndId;

import java.io.IOException;
import java.util.Collection;
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
				// 每个子命令自己决定谁能用；用不了的子命令连补全都看不到
				dispatcher.register(Commands.literal("palace")
						.requires(source -> source.getPlayer() != null)
						// 领书、新建、分享：有对应权限节点的人（默认所有人；多人总开关关着时只有房主）
						.then(Commands.literal("give")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.GIVE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(suggestions(ctx.getSource()), builder))
										.executes(PalaceCommands::give)))
						.then(Commands.literal("new")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> create(ctx, false))))
						// 笔记写在最后（路径里可以有空格、中文、斜杠，只有放在最后才能整段读进来）
						.then(Commands.literal("share")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.SHARE))
								.then(Commands.literal("everyone")
										.then(shareLevel("read", NoteAccess.Level.READ, true))
										.then(shareLevel("edit", NoteAccess.Level.EDIT, true)))
								.then(Commands.argument("player", GameProfileArgument.gameProfile())
										.then(shareLevel("read", NoteAccess.Level.READ, false))
										.then(shareLevel("edit", NoteAccess.Level.EDIT, false))))
						.then(Commands.literal("unshare")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.SHARE))
								.then(Commands.literal("everyone")
										.then(ownedNoteArgument().executes(ctx -> unshare(ctx, true))))
								.then(Commands.argument("player", GameProfileArgument.gameProfile())
										.then(ownedNoteArgument().executes(ctx -> unshare(ctx, false)))))
						// 重扫、改可见性、重读配置：管理员
						.then(Commands.literal("rescan").requires(PalaceCommands::admin).executes(PalaceCommands::rescan))
						.then(Commands.literal("reload").requires(PalaceCommands::admin).executes(PalaceCommands::reload))
						.then(Commands.literal("visibility")
								.requires(PalaceCommands::admin)
								.executes(PalaceCommands::showVisibility)
								.then(Commands.literal("everyone").executes(ctx -> setVisibility(ctx, NoteAccess.Visibility.EVERYONE)))
								.then(Commands.literal("private").executes(ctx -> setVisibility(ctx, NoteAccess.Visibility.PRIVATE))))
						// 加密：每人用自己的钥匙圈，只能加密自己是作者的笔记（多人关着时只有房主）
						.then(Commands.literal("newsecret")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> create(ctx, true))))
						.then(Commands.literal("encrypt")
								.requires(PalaceCommands::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> crypto(ctx, true))))
						.then(Commands.literal("decrypt")
								.requires(PalaceCommands::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> crypto(ctx, false))))
						.then(Commands.literal("lock").requires(PalaceCommands::canCrypto).executes(ctx -> {
							CryptoPayloads.sendAction(ctx.getSource().getPlayerOrException(), new UUID(0, 0), "lock", "", "");
							return 1;
						}))
						// 列表、查位置：谁都能用，只列出自己读得了的笔记
						.then(Commands.literal("list")
								.executes(ctx -> list(ctx, ""))
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "folder")))))
						.then(Commands.literal("where")
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(noteSuggestions(ctx.getSource()), builder))
										.executes(PalaceCommands::where)))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> shareLevel(
			String name, NoteAccess.Level level, boolean everyone) {
		return Commands.literal(name).then(ownedNoteArgument().executes(ctx -> share(ctx, level, everyone)));
	}

	// 分享命令的笔记参数：补全只列自己能分享的（自己是作者、不是加密笔记或收件箱）
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> ownedNoteArgument() {
		return Commands.argument("note", StringArgumentType.greedyString())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ownedSuggestions(ctx.getSource()), builder));
	}

	private static boolean admin(CommandSourceStack source) {
		return source.getPlayer() != null && PalaceAccess.admin(source.getPlayer());
	}

	private static boolean canCrypto(CommandSourceStack source) {
		return source.getPlayer() != null && CryptoPayloads.allowed(source.getPlayer());
	}

	/** 这个人读得了这篇吗？（命令里用：找不到人就当读不了） */
	private static boolean readable(CommandSourceStack source, NoteSummary note) {
		ServerPlayer player = source.getPlayer();
		return player != null && PalaceAccess.canRead(player, note);
	}

	/** /palace visibility：看现在的笔记可见性。 */
	private static int showVisibility(CommandContext<CommandSourceStack> ctx) {
		NoteAccess.Visibility current = PalaceServer.visibility();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.visibility_is",
				Component.translatable("command.memorypalace.visibility." + current.id())), false);
		return 1;
	}

	/** /palace visibility everyone|private：改世界设置"笔记可见性"（没写 palace-read 的笔记谁能读）。 */
	private static int setVisibility(CommandContext<CommandSourceStack> ctx, NoteAccess.Visibility value) {
		if (requireVault(ctx) == null) {
			return 0;
		}
		try {
			PalaceServer.setVisibility(value);
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("保存世界设置失败", e);
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.visibility_set",
				Component.translatable("command.memorypalace.visibility." + value.id())), true);
		return 1;
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
		// 加密要求是作者（替别人加密，别人就再也打不开）；解除加密要求能改
		boolean allowed = encrypt ? NoteAccess.isOwner(note.get().access(), PalaceAccess.who(player))
				: PalaceAccess.canEdit(player, note.get());
		if (!allowed) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.no_edit_permission", note.get().relativePath()));
			return 0;
		}
		if (note.get().encrypted() == encrypt) {
			ctx.getSource().sendFailure(Component.translatable(encrypt
					? "command.memorypalace.already_encrypted" : "command.memorypalace.not_encrypted", note.get().title()));
			return 0;
		}
		if (!encrypt) {
			// 解除加密不直接发密文：先打开这篇，玩家在打开着的笔记上点 🔓 才算数。
			// 客户端只认"自己正打开着的那份密文"，恶意服务端没法把别的笔记换进来骗出明文
			PalaceInteractions.openNote(player, note.get().id());
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.decrypt_hint"), false);
			return 1;
		}
		try {
			String body = vault.readBody(note.get().id()).orElse("");
			if (body.length() > CryptoPayloads.MAX_ACTION_BODY
					|| !CryptoPayloads.sendAction(player, note.get().id(), "encrypt", note.get().title(), body)) {
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
	 * - 房主：建在输入的位置，不写作者（和单人时代的笔记一样，归管理员）
	 * - 其他管理员：建在输入的位置，作者是自己
	 * - 普通玩家：建在自己的个人区 players/&lt;UUID&gt;/ 下，作者是自己，受配置文件里的篇数上限限制
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
		boolean admin = PalaceAccess.admin(player);
		if (!admin && !WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		int maxNotes = PalaceConfig.get().maxNotesPerPlayer();
		if (!admin && maxNotes > 0 && vault.list().stream().filter(n -> player.getUUID().equals(n.access().owner())).count() >= maxNotes) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.quota_notes", maxNotes));
			return 0;
		}
		String input = StringArgumentType.getString(ctx, "path").strip();
		String path = admin ? input : personalFolder(player) + "/" + input;
		UUID owner = PalaceAccess.isHost(player) ? null : player.getUUID();
		NoteSummary note;
		String body;
		try {
			note = vault.createNote(path, "", owner);
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
		// 读不了的笔记当作不存在：不透露"有这么一篇、放在哪里"
		Optional<NoteSummary> note = resolveNote(vault, ctx.getSource().getPlayer(), path).filter(n -> readable(ctx.getSource(), n));
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

	// /palace where、encrypt、decrypt 的补全：只有笔记，没有文件夹；只列这个人读得了的
	private static List<String> noteSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		return vault.list().stream().filter(n -> readable(source, n))
				.map(n -> n.relativePath().substring(0, n.relativePath().length() - 3)).toList();
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "path").strip();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean admin = PalaceAccess.admin(player);
		if (!admin && !GIVE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}

		// 管理员领什么都行（比如替别人布置书架）；其他人只能领自己读得了的，读不了的当作不存在
		Optional<NoteSummary> single = resolveNote(vault, player, path).filter(n -> admin || PalaceAccess.canRead(player, n));
		List<NoteSummary> notes = single.map(List::of).orElseGet(() -> {
			List<NoteSummary> folder = vault.listFolder(path);
			if (folder.isEmpty() && !admin) {
				folder = vault.listFolder(personalFolder(player) + "/" + path);
			}
			return folder.stream().filter(n -> admin || PalaceAccess.canRead(player, n)).toList();
		});
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
		List<NoteSummary> notes = (folder.isBlank() ? vault.list() : vault.listFolder(folder.strip())).stream()
				.filter(n -> readable(ctx.getSource(), n)).toList();
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

	/**
	 * /palace share &lt;玩家|everyone&gt; read|edit &lt;笔记&gt;：只有作者能分享；加密笔记和收件箱不能分享（只有作者能碰）。
	 * 写进笔记 front matter 的 palace-read / palace-edit（玩家记 UUID，改名不受影响）。
	 */
	private static int share(CommandContext<CommandSourceStack> ctx, NoteAccess.Level level, boolean everyone) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Collection<NameAndId> targets = everyone ? List.of() : GameProfileArgument.getGameProfiles(ctx, "player");
		Optional<NoteSummary> note = ownedNote(ctx, player);
		if (note.isEmpty()) {
			return 0;
		}
		List<UUID> ids = everyone ? List.of(NoteAccess.Rules.EVERYONE_ID)
				: targets.stream().map(NameAndId::id).filter(id -> !id.equals(player.getUUID())).toList();
		if (ids.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.share_self"));
			return 0;
		}
		NoteAccess.Visibility visibility = PalaceServer.visibility();
		if (!changeAccess(ctx, player, note.get(), rules -> {
			for (UUID id : ids) {
				rules = NoteAccess.share(rules, id, level, visibility);
			}
			return rules;
		})) {
			return 0;
		}
		Component who = everyone ? Component.translatable("command.memorypalace.everyone") : names(targets, player);
		ctx.getSource().sendSuccess(() -> Component.translatable(level == NoteAccess.Level.EDIT
				? "command.memorypalace.shared_edit" : "command.memorypalace.shared_read", note.get().title(), who), false);
		NoteSummary now = PalaceServer.vault().find(note.get().id()).orElse(note.get());
		if (level == NoteAccess.Level.READ && now.access().read() == null) {
			// 没写 palace-read、世界又是"所有人可读"：本来就人人能读，名单没改
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.already_public"), false);
		}
		// 告诉在线的被分享者一声（他们现在读得了，说出标题不算泄露）
		for (NameAndId target : targets) {
			ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(target.id());
			if (online != null && online != player) {
				online.sendSystemMessage(Component.translatable("command.memorypalace.shared_with_you",
						player.getName(), note.get().title(), noteCommandPath(now)));
			}
		}
		return ids.size();
	}

	/** /palace unshare &lt;玩家|everyone&gt; &lt;笔记&gt;：从 palace-read 和 palace-edit 里去掉。 */
	private static int unshare(CommandContext<CommandSourceStack> ctx, boolean everyone) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Collection<NameAndId> targets = everyone ? List.of() : GameProfileArgument.getGameProfiles(ctx, "player");
		Optional<NoteSummary> note = ownedNote(ctx, player);
		if (note.isEmpty()) {
			return 0;
		}
		List<UUID> ids = everyone ? List.of(NoteAccess.Rules.EVERYONE_ID) : targets.stream().map(NameAndId::id).toList();
		NoteAccess.Visibility visibility = PalaceServer.visibility();
		if (!changeAccess(ctx, player, note.get(), rules -> {
			for (UUID id : ids) {
				rules = NoteAccess.unshare(rules, id, visibility);
			}
			return rules;
		})) {
			return 0;
		}
		Component who = everyone ? Component.translatable("command.memorypalace.everyone") : names(targets, null);
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.unshared", note.get().title(), who), false);
		// 没有"黑名单"：这篇还对所有人公开的话，单独去掉的人照样能读，要说清楚
		NoteSummary now = PalaceServer.vault().find(note.get().id()).orElse(note.get());
		boolean stillPublic = NoteAccess.titleIsPublic(now.access(), now.ownerOnly(), visibility);
		if (!everyone && stillPublic) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.still_public"), false);
		}
		return 1;
	}

	/** 分享命令要的笔记：自己读得了、自己是作者、不是加密笔记或收件箱。不满足时已经发了提示，返回 empty。 */
	private static Optional<NoteSummary> ownedNote(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
		Vault vault = requireVault(ctx);
		if (vault == null) {
			return Optional.empty();
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = resolveNote(vault, player, path).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return Optional.empty();
		}
		if (note.get().ownerOnly()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.share_owner_only"));
			return Optional.empty();
		}
		if (!NoteAccess.isOwner(note.get().access(), PalaceAccess.who(player))) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.share_not_owner", note.get().title()));
			return Optional.empty();
		}
		if (!PalaceAccess.admin(player) && !WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return Optional.empty();
		}
		return note;
	}

	// 改分享名单；写入那一刻按文件重新检查一次"还是作者、还能分享"（文件可能刚在外部被改过）
	private static boolean changeAccess(CommandContext<CommandSourceStack> ctx, ServerPlayer player, NoteSummary note,
			java.util.function.UnaryOperator<NoteAccess.Rules> change) {
		NoteAccess.Who who = PalaceAccess.who(player);
		try {
			Vault.WriteResult result = PalaceServer.vault().updateAccess(note.id(), change,
					fresh -> !fresh.ownerOnly() && NoteAccess.isOwner(fresh.access(), who));
			switch (result) {
				case OK -> {
					return true;
				}
				case DENIED -> ctx.getSource().sendFailure(Component.translatable("command.memorypalace.share_not_owner", note.title()));
				default -> ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", note.relativePath()));
			}
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.error("修改笔记 {} 的分享设置失败", note.id(), e);
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
		}
		return false;
	}

	private static Component names(Collection<NameAndId> targets, ServerPlayer except) {
		return Component.literal(String.join(", ", targets.stream()
				.filter(t -> except == null || !t.id().equals(except.getUUID())).map(NameAndId::name).toList()));
	}

	// 聊天里给出的笔记路径（去掉 .md），对方可以直接 /palace give 它
	private static String noteCommandPath(NoteSummary note) {
		return note.relativePath().substring(0, note.relativePath().length() - 3);
	}

	/** /palace reload：重读 config/memorypalace-server.toml，并把命令列表重新发给在线玩家（能用的命令可能变了）。 */
	private static int reload(CommandContext<CommandSourceStack> ctx) {
		boolean ok = PalaceConfig.load();
		MinecraftServer server = ctx.getSource().getServer();
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			server.getCommands().sendCommands(online);
		}
		if (!ok) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.reload_failed", PalaceConfig.FILE_NAME));
			return 0;
		}
		PalaceConfig.Settings s = PalaceConfig.get();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.reloaded",
				Component.translatable(s.multiplayer() ? "command.memorypalace.on" : "command.memorypalace.off")), true);
		return 1;
	}

	/** 放玩家个人区的文件夹。 */
	static final String PLAYERS_FOLDER = NoteAccess.PLAYERS_FOLDER;

	/** 普通玩家的个人区：players/&lt;UUID&gt;。 */
	static String personalFolder(ServerPlayer player) {
		return NoteAccess.personalFolder(player.getUUID());
	}

	/** 按路径找笔记：先在自己的个人区里找（普通玩家输入的路径都相对个人区），找不到再按整个笔记库的路径找。 */
	private static Optional<NoteSummary> resolveNote(Vault vault, ServerPlayer player, String path) {
		if (player != null) {
			Optional<NoteSummary> mine = vault.resolve(personalFolder(player) + "/" + path);
			if (mine.isPresent()) {
				return mine;
			}
		}
		return vault.resolve(path);
	}

	// /palace share、unshare 的补全：自己能分享的笔记
	private static List<String> ownedSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		ServerPlayer player = source.getPlayer();
		if (vault == null || player == null) {
			return List.of();
		}
		NoteAccess.Who who = PalaceAccess.who(player);
		return vault.list().stream().filter(n -> !n.ownerOnly() && NoteAccess.isOwner(n.access(), who))
				.map(PalaceCommands::noteCommandPath).toList();
	}

	// 普通玩家新建、分享：每人每秒 1 次、最多连着 5 次；领书：每 2 秒 1 次、最多连着 4 次（一次最多 36 本）
	private static final RateLimiter WRITE_RATE = new RateLimiter(1, 5);
	private static final RateLimiter GIVE_RATE = new RateLimiter(0.5, 4);

	private static Vault requireVault(CommandContext<CommandSourceStack> ctx) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.no_vault"));
		}
		return vault;
	}

	// 补全：笔记路径（去掉 .md）+ 它们所在的文件夹。管理员看到全部；其他人只看到自己读得了的（文件夹名也不多透露）
	private static List<String> suggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		boolean admin = admin(source);
		var out = new java.util.TreeSet<String>();
		for (NoteSummary note : vault.list()) {
			if (!admin && !readable(source, note)) {
				continue;
			}
			String p = note.relativePath();
			out.add(p.substring(0, p.length() - 3));
			if (!note.folder().isEmpty()) {
				out.add(note.folder());
			}
		}
		return List.copyOf(out);
	}
}
