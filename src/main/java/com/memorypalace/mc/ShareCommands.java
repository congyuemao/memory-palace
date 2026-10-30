package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** /palace share、unshare、visibility。 */
final class ShareCommands {
	private ShareCommands() {}

	/**
	 * /palace share &lt;玩家|everyone&gt; read|edit &lt;笔记&gt;：只有作者能分享；加密笔记和收件箱不能分享（只有作者能碰）。
	 * 写进笔记 front matter 的 palace-read / palace-edit（玩家记 UUID，改名不受影响）。
	 */
	static int share(CommandContext<CommandSourceStack> ctx, NoteAccess.Level level, boolean everyone) throws CommandSyntaxException {
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
						player.getName(), note.get().title(), CommandSupport.noteCommandPath(now)));
			}
		}
		return ids.size();
	}

	/** /palace unshare &lt;玩家|everyone&gt; &lt;笔记&gt;：从 palace-read 和 palace-edit 里去掉。 */
	static int unshare(CommandContext<CommandSourceStack> ctx, boolean everyone) throws CommandSyntaxException {
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
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return Optional.empty();
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> PalaceAccess.canRead(player, n));
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
		if (!PalaceAccess.admin(player) && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return Optional.empty();
		}
		return note;
	}

	// 改分享名单；写入那一刻按文件重新检查一次"还是作者、还能分享"（文件可能刚在外部被改过）
	private static boolean changeAccess(CommandContext<CommandSourceStack> ctx, ServerPlayer player, NoteSummary note,
			UnaryOperator<NoteAccess.Rules> change) {
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

	// /palace share、unshare 的补全：自己能分享的笔记
	static List<String> ownedSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		ServerPlayer player = source.getPlayer();
		if (vault == null || player == null) {
			return List.of();
		}
		NoteAccess.Who who = PalaceAccess.who(player);
		return vault.list().stream().filter(n -> !n.ownerOnly() && NoteAccess.isOwner(n.access(), who))
				.map(CommandSupport::noteCommandPath).toList();
	}

	/** /palace visibility：看现在的笔记可见性。 */
	static int showVisibility(CommandContext<CommandSourceStack> ctx) {
		NoteAccess.Visibility current = PalaceServer.visibility();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.visibility_is",
				Component.translatable("command.memorypalace.visibility." + current.id())), false);
		return 1;
	}

	/** /palace visibility everyone|private：改世界设置"笔记可见性"（没写 palace-read 的笔记谁能读）。 */
	static int setVisibility(CommandContext<CommandSourceStack> ctx, NoteAccess.Visibility value) {
		if (CommandSupport.requireVault(ctx) == null) {
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
}
