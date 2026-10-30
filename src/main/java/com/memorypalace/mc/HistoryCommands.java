package com.memorypalace.mc;

import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.vault.NoteHistory;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** /palace history、historypage、historyview、historyrestore。 */
final class HistoryCommands {
	private HistoryCommands() {}

	/** 历史版本命令要的笔记：读得了、改得了。不满足时已经发了提示，返回 empty。 */
	private static Optional<NoteSummary> editableNote(CommandContext<CommandSourceStack> ctx, Vault vault, ServerPlayer player) {
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return Optional.empty();
		}
		// 只读的人看不到历史：不然能看到分享给他之前就删掉的内容
		if (!PalaceAccess.canEdit(player, note.get())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.no_edit_permission", note.get().relativePath()));
			return Optional.empty();
		}
		return note;
	}

	/** 历史列表每页几条。 */
	private static final int HISTORY_PAGE = 10;

	/**
	 * /palace history &lt;笔记&gt;（/palace historypage &lt;页&gt; &lt;笔记&gt;）：列出历史版本（最新的在前），
	 * 每条带"比更早一份 +几行 −几行"，可以点"查看"、"还原"；一页 HISTORY_PAGE 条，末尾有"更早的"链接。
	 */
	static int history(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null || vault.history() == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<NoteSummary> note = editableNote(ctx, vault, player);
		if (note.isEmpty()) {
			return 0;
		}
		List<NoteHistory.Version> versions;
		try {
			versions = vault.history().list(note.get().id());
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
		if (versions.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.history_empty", note.get().title()), false);
			return 0;
		}
		int pages = (versions.size() + HISTORY_PAGE - 1) / HISTORY_PAGE;
		int shown = Math.min(page, pages);
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.history_header", note.get().title(),
				versions.size(), shown, pages), false);
		String path = CommandSupport.noteCommandPath(note.get());
		for (int i = (shown - 1) * HISTORY_PAGE; i < Math.min(versions.size(), shown * HISTORY_PAGE); i++) {
			NoteHistory.Version v = versions.get(i);
			var line = Component.literal("  " + (i + 1) + ". ").append(CommandSupport.ago(v.time().toEpochMilli()))
					.append(" · ").append(Component.translatable("command.memorypalace.history_chars", v.chars()));
			if (v.added() >= 0) {
				line.append(" · ").append(Component.literal("+" + v.added()).withStyle(Style.EMPTY.withColor(0x55FF55)))
						.append(" ").append(Component.literal("−" + v.removed()).withStyle(Style.EMPTY.withColor(0xFF5555)));
			}
			if (v.encrypted()) {
				line.append(" · ").append(Component.translatable("command.memorypalace.history_encrypted"));
			}
			line.append(" ").append(CommandSupport.link("command.memorypalace.history_view", "command.memorypalace.history_view_hover",
					"/palace historyview " + v.id() + " " + path));
			line.append(" ").append(CommandSupport.link("command.memorypalace.history_restore", "command.memorypalace.history_restore_hover",
					"/palace historyrestore " + v.id() + " " + path));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		if (shown < pages) {
			ctx.getSource().sendSuccess(() -> CommandSupport.link("command.memorypalace.history_older", "command.memorypalace.history_older_hover",
					"/palace historypage " + (shown + 1) + " " + path), false);
		}
		return versions.size();
	}

	/**
	 * /palace historyview|historyrestore &lt;版本&gt; &lt;笔记&gt;：把这个版本发给客户端只读查看，或者让客户端还原
	 * （加密笔记要客户端解开旧版本、用现在的锁重新加密：取消掉的阅读密码不会跟着旧版本回来）。
	 */
	static int historyAction(CommandContext<CommandSourceStack> ctx, boolean restore) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null || vault.history() == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Optional<NoteSummary> note = editableNote(ctx, vault, player);
		if (note.isEmpty()) {
			return 0;
		}
		String versionId = StringArgumentType.getString(ctx, "version");
		try {
			Optional<String> old = vault.history().read(note.get().id(), versionId);
			Optional<Vault.Read> current = vault.readNote(note.get().id());
			if (old.isEmpty() || current.isEmpty()) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.history_missing"));
				return 0;
			}
			boolean oldEncrypted = Ciphertext.isEncrypted(old.get());
			boolean nowEncrypted = current.get().note().encrypted();
			if ((oldEncrypted || nowEncrypted) && !CryptoPayloads.allowed(player)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_denied"));
				return 0;
			}
			if (!restore) {
				if (!CryptoPayloads.sendAction(player, note.get().id(), CryptoAction.VIEW_VERSION, note.get().title(), old.get())) {
					ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
					return 0;
				}
				return 1;
			}
			// 加密的旧版本不能还原进已经解除加密的笔记：那等于把它以明文存回
			if (oldEncrypted && !nowEncrypted) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.history_restore_encrypted"));
				return 0;
			}
			if (!PalaceAccess.admin(player) && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
				return 0;
			}
			// 现在的正文和旧版本一起发过去（"长度:现在的正文旧版本"）：客户端要用现在的锁重新加密，保存时也要核对没被别人改过
			String now = current.get().body();
			if (!CryptoPayloads.sendAction(player, note.get().id(), CryptoAction.RESTORE_VERSION, note.get().title(),
					now.length() + ":" + now + old.get())) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
				return 0;
			}
			return 1;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.translatable("message.memorypalace.read_failed"));
			return 0;
		}
	}
}
