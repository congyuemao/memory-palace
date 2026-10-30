package com.memorypalace.mc;

import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/** /palace encrypt、decrypt、lock、lockfolder、folderpassword、unlockfolder、sharepassword、unsharepassword。 */
final class CryptoCommands {
	private CryptoCommands() {}

	/** /palace lock：让客户端锁上保险库。 */
	static int lock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CryptoPayloads.sendAction(ctx.getSource().getPlayerOrException(), new UUID(0, 0), CryptoAction.LOCK, "", "");
		return 1;
	}

	/**
	 * /palace encrypt|decrypt &lt;笔记&gt;：服务端没有钥匙，所以只把正文发给客户端，由客户端加密/解密后存回来。
	 */
	static int crypto(CommandContext<CommandSourceStack> ctx, boolean encrypt) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
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
					|| !CryptoPayloads.sendAction(player, note.get().id(), CryptoAction.ENCRYPT, note.get().title(), body)) {
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
	 * /palace sharepassword|unsharepassword &lt;笔记&gt;：给自己的加密笔记设置 / 修改 / 取消阅读密码。
	 * 服务端没有钥匙：检查通过后把密文发给客户端，客户端换一把新的内容钥匙重新加密（加上或去掉 p 锁）再存回来。
	 */
	static int readPassword(CommandContext<CommandSourceStack> ctx, boolean set) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!CryptoPayloads.allowed(player)) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.crypto_denied"));
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		if (!NoteAccess.isOwner(note.get().access(), PalaceAccess.who(player))) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.share_not_owner", note.get().title()));
			return 0;
		}
		if (note.get().inbox()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.read_password_inbox"));
			return 0;
		}
		if (!note.get().encrypted()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.read_password_not_encrypted", note.get().title()));
			return 0;
		}
		if (!PalaceAccess.admin(player) && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		try {
			Optional<Vault.Read> read = vault.readNote(note.get().id());
			if (read.isEmpty()) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
				return 0;
			}
			String body = read.get().body();
			if (!set && SharePayloads.passwordLock(read.get().note(), body).isEmpty()) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.no_read_password", note.get().title()));
				return 0;
			}
			if (body.length() > CryptoPayloads.MAX_ACTION_BODY * 4
					|| !CryptoPayloads.sendAction(player, note.get().id(), set ? CryptoAction.SET_READ_PASSWORD : CryptoAction.REMOVE_READ_PASSWORD,
							note.get().title(), body)) {
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
	 * /palace lockfolder &lt;文件夹&gt;：给文件夹单独设密码。这里只检查能不能锁，通过后让客户端设密码、写锁文件，
	 * 再由客户端请求把里面的笔记逐篇加密（见 FolderPayloads）。普通玩家输入的路径相对自己的个人区。
	 */
	static int lockFolder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String input = StringArgumentType.getString(ctx, "folder");
		String folder = CommandSupport.folderFromInput(vault, player, input);
		try {
			if (!FolderPayloads.mayManage(player, folder)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_denied"));
				return 0;
			}
			if (!Files.isDirectory(vault.root().resolve(folder))) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", input));
				return 0;
			}
			// 这个文件夹自己已经有锁：上次没加密完（保存限速、中途上锁、掉线）也能用同一条命令接着做完
			Optional<String> existing = FolderPayloads.lockAt(vault, folder);
			if (existing.isPresent()) {
				CryptoPayloads.sendAction(player, new UUID(0, 0), CryptoAction.RESUME_FOLDER, folder, existing.get());
				return 1;
			}
			if (FolderLocks.lockedFolder(vault, folder).isPresent() || FolderPayloads.hasLockInside(vault, folder)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_already_locked"));
				return 0;
			}
			NoteAccess.Who who = PalaceAccess.who(player);
			if (FolderPayloads.notesIn(vault, folder).stream().anyMatch(n -> !NoteAccess.isOwner(n.access(), who))) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_not_all_yours"));
				return 0;
			}
		} catch (IOException | IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", input));
			return 0;
		}
		CryptoPayloads.sendAction(player, new UUID(0, 0), CryptoAction.LOCK_FOLDER, folder, "");
		return 1;
	}

	/**
	 * /palace folderpassword、/palace unlockfolder &lt;文件夹&gt;：检查能管这个文件夹、它确实有锁，
	 * 然后把锁文件交给客户端（CryptoAction.FOLDER_PASSWORD / UNLOCK_FOLDER，title = 文件夹，body = 锁的内容），
	 * 由客户端要求输入当前文件夹密码（忘记时可以用恢复密钥）再继续。
	 */
	static int folderLockAction(CommandContext<CommandSourceStack> ctx, CryptoAction action) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String input = StringArgumentType.getString(ctx, "folder");
		String folder = CommandSupport.folderFromInput(vault, player, input);
		Optional<String> lock;
		try {
			if (!FolderPayloads.mayManage(player, folder)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_denied"));
				return 0;
			}
			lock = FolderPayloads.lockAt(vault, folder);
		} catch (IOException | IllegalArgumentException e) {
			lock = Optional.empty();
		}
		if (lock.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_not_locked", input.strip()));
			return 0;
		}
		CryptoPayloads.sendAction(player, new UUID(0, 0), action, folder, lock.get());
		return 1;
	}

	// /palace folderpassword、unlockfolder 的补全：自己能管的、已经上锁的文件夹
	static List<String> lockedFolderSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		ServerPlayer player = source.getPlayer();
		if (vault == null || player == null) {
			return List.of();
		}
		return folderSuggestions(source).stream().filter(f -> {
			try {
				return FolderPayloads.lockAt(vault, f).isPresent();
			} catch (IOException | IllegalArgumentException e) {
				return false;
			}
		}).toList();
	}

	// /palace lockfolder 的补全：自己能锁的、还没锁的、有笔记的文件夹
	static List<String> folderSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		ServerPlayer player = source.getPlayer();
		if (vault == null || player == null) {
			return List.of();
		}
		var out = new TreeSet<String>();
		for (NoteSummary note : vault.list()) {
			String f = note.folder();
			while (!f.isEmpty()) {
				if (FolderPayloads.mayManage(player, f)) {
					out.add(f);
				}
				int slash = f.lastIndexOf('/');
				f = slash < 0 ? "" : f.substring(0, slash);
			}
		}
		return List.copyOf(out);
	}

	// /palace sharepassword、unsharepassword 的补全：自己的加密笔记（收件箱除外）
	static List<String> secretSuggestions(CommandSourceStack source) {
		Vault vault = PalaceServer.vault();
		ServerPlayer player = source.getPlayer();
		if (vault == null || player == null) {
			return List.of();
		}
		NoteAccess.Who who = PalaceAccess.who(player);
		return vault.list().stream().filter(n -> n.encrypted() && !n.inbox() && NoteAccess.isOwner(n.access(), who))
				.map(CommandSupport::noteCommandPath).toList();
	}
}
