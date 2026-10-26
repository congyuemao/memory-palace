package com.memorypalace.mc;

import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

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
						.then(Commands.literal("rescan").executes(PalaceCommands::rescan))));
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
