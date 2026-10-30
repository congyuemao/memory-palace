package com.memorypalace.mc;

import com.memorypalace.core.vault.NoteAccess;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;

/**
 * /palace 命令树。各子命令的实现按主题分在 NoteCommands、ShareCommands、CryptoCommands、HistoryCommands、
 * LocationCommands，共用的在 CommandSupport。
 * /palace give &lt;笔记或文件夹&gt;   领取笔记书（文件夹 = 里面每篇各一本）
 * /palace list [文件夹]          列出笔记
 * /palace rescan                重新扫描笔记库
 */
public final class PalaceCommands {
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
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.suggestions(ctx.getSource()), builder))
										.executes(NoteCommands::give)))
						.then(Commands.literal("new")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> NoteCommands.create(ctx, false))))
						.then(Commands.literal("newfolder")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(NoteCommands::createFolder)))
						// 改名：新名字可以带空格，要加引号，例如 /palace rename "新 名字" 旧笔记
						.then(Commands.literal("rename")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("name", StringArgumentType.string())
										.then(Commands.argument("note", StringArgumentType.greedyString())
												.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
												.executes(NoteCommands::rename))))
						// 删除：先问一次，点"确认删除"才真的挪进回收站
						.then(Commands.literal("delete")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> NoteCommands.delete(ctx, false))))
						.then(Commands.literal("deleteconfirm")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> NoteCommands.delete(ctx, true))))
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
										.then(ownedNoteArgument().executes(ctx -> ShareCommands.unshare(ctx, true))))
								.then(Commands.argument("player", GameProfileArgument.gameProfile())
										.then(ownedNoteArgument().executes(ctx -> ShareCommands.unshare(ctx, false)))))
						// 重扫、改可见性、重读配置：管理员
						.then(Commands.literal("rescan").requires(CommandSupport::admin).executes(NoteCommands::rescan))
						.then(Commands.literal("reload").requires(CommandSupport::admin).executes(NoteCommands::reload))
						.then(Commands.literal("visibility")
								.requires(CommandSupport::admin)
								.executes(ShareCommands::showVisibility)
								.then(Commands.literal("everyone").executes(ctx -> ShareCommands.setVisibility(ctx, NoteAccess.Visibility.EVERYONE)))
								.then(Commands.literal("private").executes(ctx -> ShareCommands.setVisibility(ctx, NoteAccess.Visibility.PRIVATE))))
						// 加密：每人用自己的钥匙圈，只能加密自己是作者的笔记（多人关着时只有房主）
						.then(Commands.literal("newsecret")
								.requires(source -> PalaceAccess.allowed(source, PalaceAccess.CREATE))
								.then(Commands.argument("path", StringArgumentType.greedyString())
										.executes(ctx -> NoteCommands.create(ctx, true))))
						.then(Commands.literal("encrypt")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.crypto(ctx, true))))
						.then(Commands.literal("decrypt")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.crypto(ctx, false))))
						// 文件夹独立密码：给一个文件夹单独设密码，里面的笔记用文件夹钥匙加密，保险库密码打不开
						.then(Commands.literal("lockfolder")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CryptoCommands.folderSuggestions(ctx.getSource()), builder))
										.executes(CryptoCommands::lockFolder)))
						.then(Commands.literal("folderpassword")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CryptoCommands.lockedFolderSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.folderLockAction(ctx, CryptoAction.FOLDER_PASSWORD))))
						.then(Commands.literal("unlockfolder")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CryptoCommands.lockedFolderSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.folderLockAction(ctx, CryptoAction.UNLOCK_FOLDER))))
						// 单篇阅读密码：把一篇自己的加密笔记分享给知道密码的人（只读）
						.then(Commands.literal("sharepassword")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CryptoCommands.secretSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.readPassword(ctx, true))))
						.then(Commands.literal("unsharepassword")
								.requires(CommandSupport::canCrypto)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CryptoCommands.secretSuggestions(ctx.getSource()), builder))
										.executes(ctx -> CryptoCommands.readPassword(ctx, false))))
						// 历史版本：能改这篇的人能看、能还原
						.then(Commands.literal("history")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(ctx -> HistoryCommands.history(ctx, 1))))
						.then(Commands.literal("historypage")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("page", IntegerArgumentType.integer(1))
										.then(Commands.argument("note", StringArgumentType.greedyString())
												.executes(ctx -> HistoryCommands.history(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
						.then(Commands.literal("historyview")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("version", StringArgumentType.word())
										.then(Commands.argument("note", StringArgumentType.greedyString())
												.executes(ctx -> HistoryCommands.historyAction(ctx, false)))))
						.then(Commands.literal("historyrestore")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("version", StringArgumentType.word())
										.then(Commands.argument("note", StringArgumentType.greedyString())
												.executes(ctx -> HistoryCommands.historyAction(ctx, true)))))
						.then(Commands.literal("lock").requires(CommandSupport::canCrypto).executes(CryptoCommands::lock))
						// 列表、查位置：谁都能用，只列出自己读得了的笔记
						.then(Commands.literal("list")
								.executes(ctx -> NoteCommands.list(ctx, ""))
								.then(Commands.argument("folder", StringArgumentType.greedyString())
										.executes(ctx -> NoteCommands.list(ctx, StringArgumentType.getString(ctx, "folder")))))
						.then(Commands.literal("where")
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(LocationCommands::where)))
						// 打开一篇笔记（/palace backlinks 列出来的条目点一下就是这个命令）
						.then(Commands.literal("open")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(NoteCommands::open)))
						// 反向链接：哪些笔记链接到了这一篇
						.then(Commands.literal("backlinks")
								.requires(CommandSupport::enabled)
								.then(Commands.argument("note", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CommandSupport.noteSuggestions(ctx.getSource()), builder))
										.executes(NoteCommands::backlinks)))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> shareLevel(String name, NoteAccess.Level level, boolean everyone) {
		return Commands.literal(name).then(ownedNoteArgument().executes(ctx -> ShareCommands.share(ctx, level, everyone)));
	}

	// 分享命令的笔记参数：补全只列自己能分享的（自己是作者、不是加密笔记或收件箱）
	private static RequiredArgumentBuilder<CommandSourceStack, String> ownedNoteArgument() {
		return Commands.argument("note", StringArgumentType.greedyString())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ShareCommands.ownedSuggestions(ctx.getSource()), builder));
	}
}
