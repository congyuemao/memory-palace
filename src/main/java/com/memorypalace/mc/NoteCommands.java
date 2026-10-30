package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteLinks;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** /palace give、new、newsecret、newfolder、rename、delete、list、open、backlinks、rescan、reload。 */
final class NoteCommands {
	private NoteCommands() {}

	// 领书：每 2 秒 1 次、最多连着 4 次（一次最多 36 本）
	private static final RateLimiter GIVE_RATE = new RateLimiter(0.5, 4);

	// 一次最多领这么多本，防止手滑把背包塞爆
	private static final int MAX_GIVE = 36;

	static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
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
		Optional<NoteSummary> single = CommandSupport.resolveNote(vault, player, path).filter(n -> admin || PalaceAccess.canRead(player, n));
		List<NoteSummary> notes = single.map(List::of).orElseGet(() -> {
			List<NoteSummary> folder = vault.listFolder(path);
			if (folder.isEmpty() && !admin) {
				folder = vault.listFolder(CommandSupport.personalFolder(player) + "/" + path);
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

	static int list(CommandContext<CommandSourceStack> ctx, String folder) {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		List<NoteSummary> notes = (folder.isBlank() ? vault.list() : vault.listFolder(folder.strip())).stream()
				.filter(n -> CommandSupport.readable(ctx.getSource(), n)).toList();
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.list_header", notes.size()), false);
		for (NoteSummary note : notes) {
			ctx.getSource().sendSuccess(() -> Component.literal("  " + note.relativePath() + "  —  " + note.title()), false);
		}
		return notes.size();
	}

	/**
	 * /palace new &lt;文件夹/文件名&gt;：新建笔记，给一本笔记书，并直接打开游戏内编辑器。
	 * /palace newsecret：同上，但先加密（客户端会要求解锁或第一次设置密码）。
	 * - 房主：建在输入的位置，不写作者（和单人时代的笔记一样，归管理员）
	 * - 其他管理员：建在输入的位置，作者是自己
	 * - 普通玩家：建在自己的个人区 players/&lt;UUID&gt;/ 下，作者是自己，受配置文件里的篇数上限限制
	 */
	static int create(CommandContext<CommandSourceStack> ctx, boolean secret) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String input = StringArgumentType.getString(ctx, "path").strip();
		Optional<Created> created = createNote(player, input, "", secret, error -> ctx.getSource().sendFailure(error));
		if (created.isEmpty()) {
			return 0;
		}
		player.getInventory().placeItemBackInInventory(NoteBooks.create(created.get().note()), Prediction.SERVER_ONLY);
		openCreated(player, created.get(), secret);
		ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.created", created.get().note().relativePath()), false);
		return 1;
	}

	/**
	 * /palace newfolder &lt;文件夹&gt;：新建一个空文件夹（可以多层）。房主、管理员建在输入的位置，
	 * 普通玩家建在自己的个人区里。之后可以 /palace lockfolder 给它设密码，或者 /palace new 文件夹/笔记 往里放笔记。
	 */
	static int createFolder(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean admin = PalaceAccess.admin(player);
		if (!admin && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		String input = StringArgumentType.getString(ctx, "path").strip();
		try {
			String folder = vault.createFolder(admin ? input : CommandSupport.personalFolder(player) + "/" + input);
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.folder_created", folder), false);
			return 1;
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_create_failed", e.getMessage()));
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("新建文件夹失败", e);
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.folder_create_failed", e.getMessage()));
		}
		return 0;
	}

	/** /palace rename "&lt;新名字&gt;" &lt;笔记&gt;：只改文件名、留在原来的文件夹；只有作者（无主笔记是管理员）能改。 */
	static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		boolean admin = PalaceAccess.admin(player);
		if (!admin && !NoteAccess.isOwner(note.get().access(), PalaceAccess.who(player))) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.owner_only", note.get().relativePath()));
			return 0;
		}
		if (!admin && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		try {
			NoteSummary renamed = vault.renameNote(note.get().id(), StringArgumentType.getString(ctx, "name"));
			NoteBooks.refreshCarried(ctx.getSource().getServer(), renamed);
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.renamed",
					note.get().relativePath(), renamed.relativePath()), false);
			return 1;
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.rename_failed", e.getMessage()));
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("笔记改名失败", e);
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.rename_failed", e.getMessage()));
		}
		return 0;
	}

	/**
	 * /palace delete &lt;笔记或文件夹&gt;：先问一次，点"确认删除"（/palace deleteconfirm）才挪进回收站 .palace/trash/，
	 * 不真的删掉。笔记只有作者能删；文件夹：管理员都能删，普通玩家只能删自己个人区里、里面全是自己笔记的。
	 */
	static int delete(CommandContext<CommandSourceStack> ctx, boolean confirmed) throws CommandSyntaxException {
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean admin = PalaceAccess.admin(player);
		NoteAccess.Who who = PalaceAccess.who(player);
		String input = StringArgumentType.getString(ctx, "path").strip().replaceAll("[/\\\\]+$", "");
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, input).filter(n -> PalaceAccess.canRead(player, n));
		String target;
		String shown;
		if (note.isPresent()) {
			if (!admin && !NoteAccess.isOwner(note.get().access(), who)) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.owner_only", note.get().relativePath()));
				return 0;
			}
			target = note.get().relativePath();
			shown = CommandSupport.noteCommandPath(note.get());
		} else {
			String folder = CommandSupport.folderFromInput(vault, player, input);
			if (folder.isEmpty() || folder.startsWith(".") || !Files.isDirectory(vault.root().resolve(folder))) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", input));
				return 0;
			}
			if (!admin && (!folder.startsWith(CommandSupport.personalFolder(player) + "/")
					|| FolderPayloads.notesIn(vault, folder).stream().anyMatch(n -> !NoteAccess.isOwner(n.access(), who)))) {
				ctx.getSource().sendFailure(Component.translatable("command.memorypalace.delete_folder_denied"));
				return 0;
			}
			target = folder;
			shown = folder;
		}
		if (!confirmed) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.delete_confirm", target)
					.append(" ").append(CommandSupport.link("command.memorypalace.delete_yes", "command.memorypalace.delete_yes_hover",
							"/palace deleteconfirm " + shown)), false);
			return 1;
		}
		if (!admin && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		try {
			String trashed = vault.moveToTrash(target, Instant.now());
			ctx.getSource().sendSuccess(() -> Component.translatable("command.memorypalace.deleted", target, trashed), false);
			return 1;
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.delete_failed", e.getMessage()));
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("删除失败", e);
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.delete_failed", e.getMessage()));
		}
		return 0;
	}

	/** 新建好的笔记：note、它现在的正文、它在不在被锁的文件夹里（在的话是哪个）。 */
	record Created(NoteSummary note, String body, Optional<String> lockedFolder) {}

	/**
	 * 新建一篇笔记（/palace new、/palace newsecret、右键空白记忆笔记、抄本共用），检查权限、限速、篇数上限、被锁的文件夹。
	 * - 房主、管理员：建在 input 这个位置；普通玩家：建在自己的个人区 players/&lt;UUID&gt;/ 下
	 * - body 为 "" 时用默认的一行标题
	 * 失败时把原因交给 fail，返回 empty。
	 */
	static Optional<Created> createNote(ServerPlayer player, String input, String body, boolean secret,
			Consumer<Component> fail) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			fail.accept(Component.translatable("message.memorypalace.no_vault"));
			return Optional.empty();
		}
		if (!PalaceAccess.allowed(player, PalaceAccess.CREATE)) {
			fail.accept(Component.translatable("message.memorypalace.create_denied"));
			return Optional.empty();
		}
		if (secret && !CryptoPayloads.allowed(player)) {
			fail.accept(Component.translatable("command.memorypalace.crypto_denied"));
			return Optional.empty();
		}
		boolean admin = PalaceAccess.admin(player);
		if (!admin && !CommandSupport.WRITE_RATE.tryTake(player.getUUID())) {
			fail.accept(Component.translatable("command.memorypalace.too_fast"));
			return Optional.empty();
		}
		int maxNotes = PalaceConfig.get().maxNotesPerPlayer();
		if (!admin && maxNotes > 0 && vault.list().stream().filter(n -> player.getUUID().equals(n.access().owner())).count() >= maxNotes) {
			fail.accept(Component.translatable("command.memorypalace.quota_notes", maxNotes));
			return Optional.empty();
		}
		int maxChars = PalaceConfig.get().maxNoteChars();
		if (!admin && maxChars > 0 && body.length() > maxChars) {
			fail.accept(Component.translatable("message.memorypalace.too_long_to_copy", maxChars));
			return Optional.empty();
		}
		String path = admin ? input : CommandSupport.personalFolder(player) + "/" + input;
		UUID owner = PalaceAccess.isHost(player) ? null : player.getUUID();
		// 建在被锁的文件夹里：只有能管这个文件夹的人能建，建好马上用文件夹钥匙加密
		Optional<String> locked;
		try {
			String parent = path.replace('\\', '/');
			parent = parent.contains("/") ? parent.substring(0, parent.lastIndexOf('/')) : "";
			locked = FolderLocks.lockedFolder(vault, parent);
		} catch (IllegalArgumentException e) {
			locked = Optional.empty(); // 路径不合法：交给 createNote 报错
		}
		if (locked.isPresent() && !FolderPayloads.mayManage(player, locked.get())) {
			fail.accept(Component.translatable("command.memorypalace.folder_denied"));
			return Optional.empty();
		}
		// 被锁的文件夹里不能先存明文（抄本的正文要等客户端加密好再存）
		String initial = locked.isPresent() ? "" : body;
		try {
			NoteSummary note = vault.createNote(path, initial, owner);
			return Optional.of(new Created(note, vault.readBody(note.id()).orElse(""), locked));
		} catch (IllegalArgumentException e) {
			fail.accept(Component.translatable("command.memorypalace.new_failed", e.getMessage()));
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("新建笔记失败：{}", path, e);
			fail.accept(Component.translatable("message.memorypalace.read_failed"));
		}
		return Optional.empty();
	}

	/** 新建好之后：被锁的文件夹里的、要加密的先让客户端加密，其余直接打开游戏内编辑器。 */
	static void openCreated(ServerPlayer player, Created created, boolean secret) {
		NoteSummary note = created.note();
		String body = created.body();
		if (created.lockedFolder().isPresent()) {
			CryptoPayloads.sendAction(player, note.id(), CryptoAction.ENCRYPT_NEW_FOLDER, note.title(), body);
		} else if (secret) {
			CryptoPayloads.sendAction(player, note.id(), CryptoAction.ENCRYPT_NEW, note.title(), body);
		} else if (body.length() <= OpenNotePayload.MAX_BODY) {
			ServerPlayNetworking.send(player, new EditPayloads.OpenEditor(note.id(),
					OpenNotePayload.clip(note.title(), OpenNotePayload.MAX_TITLE), body));
		}
	}

	/** /palace open &lt;笔记&gt;：直接打开这篇（读不了的当作不存在）。 */
	static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> CommandSupport.readable(ctx.getSource(), n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		return PalaceInteractions.openNote(player, note.get().id()) ? 1 : 0;
	}

	/**
	 * /palace backlinks &lt;笔记&gt;：列出链接到这一篇的笔记（只列读得了的明文笔记，加密笔记的正文服务端读不出来）。
	 * 要读遍笔记库，放在后台线程做；每人同一时间只跑一个。
	 */
	static int backlinks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Vault vault = CommandSupport.requireVault(ctx);
		if (vault == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "note").strip();
		Optional<NoteSummary> note = CommandSupport.resolveNote(vault, player, path).filter(n -> CommandSupport.readable(ctx.getSource(), n));
		if (note.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.not_found", path));
			return 0;
		}
		if (!BACKLINKS_RUNNING.add(player.getUUID())) {
			ctx.getSource().sendFailure(Component.translatable("command.memorypalace.too_fast"));
			return 0;
		}
		NoteSummary target = note.get();
		// 在服务端线程上先把"这个人能读哪些"定下来，后台线程只读文件
		Set<UUID> visible = new HashSet<>();
		for (NoteSummary n : vault.list()) {
			if (PalaceAccess.canRead(player, n)) {
				visible.add(n.id());
			}
		}
		MinecraftServer server = ctx.getSource().getServer();
		Thread.ofVirtual().name("memorypalace-backlinks").start(() -> {
			List<NoteSummary> found;
			try {
				found = NoteLinks.backlinks(vault, target, n -> visible.contains(n.id()));
			} finally {
				BACKLINKS_RUNNING.remove(player.getUUID());
			}
			server.execute(() -> {
				if (player.hasDisconnected()) {
					return;
				}
				String title = target.relativePath().substring(0, target.relativePath().length() - 3);
				if (found.isEmpty()) {
					player.sendSystemMessage(Component.translatable("command.memorypalace.backlinks_none", title));
					return;
				}
				player.sendSystemMessage(Component.translatable("command.memorypalace.backlinks_header", found.size(), title));
				for (NoteSummary n : found.subList(0, Math.min(found.size(), MAX_BACKLINKS))) {
					String rel = n.relativePath().substring(0, n.relativePath().length() - 3);
					player.sendSystemMessage(Component.literal("  ").append(Component.literal(rel)
							.withStyle(Style.EMPTY.withColor(0x55FFFF).withUnderlined(true)
									.withClickEvent(new ClickEvent.RunCommand("/palace open " + rel))
									.withHoverEvent(new HoverEvent.ShowText(Component.translatable("command.memorypalace.backlinks_open"))))));
				}
				if (found.size() > MAX_BACKLINKS) {
					player.sendSystemMessage(Component.translatable("command.memorypalace.backlinks_more", found.size() - MAX_BACKLINKS));
				}
			});
		});
		return 1;
	}

	private static final int MAX_BACKLINKS = 30;
	private static final Set<UUID> BACKLINKS_RUNNING = ConcurrentHashMap.newKeySet();

	static int rescan(CommandContext<CommandSourceStack> ctx) {
		Vault vault = CommandSupport.requireVault(ctx);
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

	/** /palace reload：重读 config/memorypalace-server.toml，并把命令列表重新发给在线玩家（能用的命令可能变了）。 */
	static int reload(CommandContext<CommandSourceStack> ctx) {
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
}
