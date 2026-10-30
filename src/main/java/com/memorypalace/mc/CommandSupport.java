package com.memorypalace.mc;

import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** /palace 各子命令共用：权限判断、找笔记、路径、限速、补全、聊天里的链接。 */
final class CommandSupport {
	private CommandSupport() {}

	// 普通玩家新建、分享：每人每秒 1 次、最多连着 5 次
	static final RateLimiter WRITE_RATE = new RateLimiter(1, 5);

	static boolean admin(CommandSourceStack source) {
		return source.getPlayer() != null && PalaceAccess.admin(source.getPlayer());
	}

	static boolean enabled(CommandSourceStack source) {
		return source.getPlayer() != null && PalaceAccess.enabled(source.getPlayer());
	}

	static boolean canCrypto(CommandSourceStack source) {
		return source.getPlayer() != null && CryptoPayloads.allowed(source.getPlayer());
	}

	/** 这个人读得了这篇吗？（命令里用：找不到人就当读不了） */
	static boolean readable(CommandSourceStack source, NoteSummary note) {
		ServerPlayer player = source.getPlayer();
		return player != null && PalaceAccess.canRead(player, note);
	}

	static Vault requireVault(CommandContext<CommandSourceStack> ctx) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.no_vault"));
		}
		return vault;
	}

	/** 按路径找笔记：先在自己的个人区里找（普通玩家输入的路径都相对个人区），找不到再按整个笔记库的路径找。 */
	static Optional<NoteSummary> resolveNote(Vault vault, ServerPlayer player, String path) {
		if (player != null) {
			Optional<NoteSummary> mine = vault.resolve(personalFolder(player) + "/" + path);
			if (mine.isPresent()) {
				return mine;
			}
		}
		return vault.resolve(path);
	}

	/** 放玩家个人区的文件夹。 */
	static final String PLAYERS_FOLDER = NoteAccess.PLAYERS_FOLDER;

	/** 普通玩家的个人区：players/&lt;UUID&gt;。 */
	static String personalFolder(ServerPlayer player) {
		return NoteAccess.personalFolder(player.getUUID());
	}

	/** 命令里输入的文件夹：先在自己的个人区里找，没有再按整个笔记库的路径。去掉首尾空白和结尾的 "/"。 */
	static String folderFromInput(Vault vault, ServerPlayer player, String raw) {
		String input = raw.strip().replace('\\', '/');
		while (input.endsWith("/")) {
			input = input.substring(0, input.length() - 1);
		}
		String mine = personalFolder(player) + "/" + input;
		return !input.isEmpty() && Files.isDirectory(vault.root().resolve(mine)) ? mine : input;
	}

	// 聊天里给出的笔记路径（去掉 .md），对方可以直接 /palace give 它
	static String noteCommandPath(NoteSummary note) {
		return note.relativePath().substring(0, note.relativePath().length() - 3);
	}

	static Component link(String text, String hover, String command) {
		return Component.translatable(text).withStyle(Style.EMPTY.withColor(0x55FFFF).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable(hover))));
	}

	static Component ago(long millis) {
		long minutes = Math.max(0, (System.currentTimeMillis() - millis) / 60_000);
		if (minutes < 1) return Component.translatable("command.memorypalace.ago.now");
		if (minutes < 60) return Component.translatable("command.memorypalace.ago.minutes", minutes);
		if (minutes < 60 * 24) return Component.translatable("command.memorypalace.ago.hours", minutes / 60);
		return Component.translatable("command.memorypalace.ago.days", minutes / (60 * 24));
	}

	// /palace where、encrypt、decrypt 的补全：只有笔记，没有文件夹；只列这个人读得了的
	static List<String> noteSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		return vault.list().stream().filter(n -> readable(source, n))
				.map(n -> n.relativePath().substring(0, n.relativePath().length() - 3)).toList();
	}

	// 补全：笔记路径（去掉 .md）+ 它们所在的文件夹。管理员看到全部；其他人只看到自己读得了的（文件夹名也不多透露）
	static List<String> suggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return List.of();
		}
		boolean admin = admin(source);
		var out = new TreeSet<String>();
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
