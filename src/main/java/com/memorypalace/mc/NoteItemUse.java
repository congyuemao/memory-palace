package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 右键记忆笔记物品（服务端）：
 * - 空白记忆笔记 / 空白上锁笔记：在自己的个人区新建一篇（"记忆笔记/年-月-日 时-分-秒"），这本书换成那篇的笔记书；
 *   普通的直接打开编辑器，上锁的先让客户端加密
 * - 空白抄本：把原笔记抄成独立的新笔记（"抄本/原文件名 抄本"）。普通笔记服务端直接复制正文；
 *   加密笔记服务端没有钥匙，先建一篇空的，再让客户端解开原笔记、按新笔记重新加密存进去（密文绑定笔记 ID，不能直接复制）
 * - 待上锁的笔记书：作者右键时让客户端加密这篇（和 /palace encrypt 一样）
 * 客户端那边，这些"服务端让我加密 / 打开编辑器"的请求只在玩家刚右键过这本书时才认（见 PalaceInteractions.takeLocalItemUse）。
 */
final class NoteItemUse {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT);
	private static final int MAX_TRIES = 20;

	private NoteItemUse() {}

	// 右键这些物品：每人每 2 秒 1 次、最多连着 3 次。抄本、待上锁要整篇读文件（待上锁还要整篇发回去），
	// 必须在读之前就挡住，不然一个小小的右键包就能换来几百 KB 的读盘和发送
	private static final RateLimiter ITEM_RATE = new RateLimiter(0.5, 3);
	// 待上锁：发出加密请求之后 30 秒内同一个人同一篇不再发（客户端还在输密码、加密）
	private static final long LOCK_INFLIGHT_MILLIS = 30_000;
	private static final java.util.Map<String, Long> LOCK_INFLIGHT = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean tooFast(ServerPlayer player) {
		if (PalaceAccess.admin(player) || ITEM_RATE.tryTake(player.getUUID())) {
			return false;
		}
		player.sendSystemMessage(Component.translatable("command.memorypalace.too_fast"));
		return true;
	}

	/** 右键空白笔记（含上锁、抄本）。 */
	static void useBlank(ServerPlayer player, ItemStack stack) {
		if (tooFast(player)) {
			return;
		}
		Optional<UUID> source = NoteItems.copyOf(stack);
		if (source.isPresent()) {
			copy(player, stack, source.get());
			return;
		}
		boolean secret = NoteItems.isBlankLocked(stack);
		String name = "记忆笔记/" + LocalDateTime.now().format(STAMP);
		Optional<PalaceCommands.Created> created = PalaceCommands.createNote(player, name, "", secret, player::sendSystemMessage);
		if (created.isEmpty()) {
			return;
		}
		replace(player, stack, created.get().note());
		PalaceCommands.openCreated(player, created.get(), secret);
	}

	/** 这本空白笔记用掉一本（创造模式不用掉），换成新笔记的笔记书。 */
	private static void replace(ServerPlayer player, ItemStack stack, NoteSummary note) {
		stack.consume(1, player);
		player.getInventory().placeItemBackInInventory(NoteBooks.create(note), Prediction.SERVER_ONLY);
		player.sendSystemMessage(Component.translatable("command.memorypalace.created", note.relativePath()));
	}

	private static void copy(ServerPlayer player, ItemStack stack, UUID sourceId) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.no_vault"));
			return;
		}
		// 先按目录卡片挡掉读不了的、文件太大的，再读文件
		if (vault.find(sourceId).filter(n -> PalaceAccess.canRead(player, n)).isEmpty()
				|| PalaceInteractions.tooLarge(vault, sourceId)) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.note_unavailable"));
			return;
		}
		try {
			// 只有读得了原笔记的人能抄（书只是门牌：拿到别人的抄本也抄不出东西）。按文件此刻的内容再查一次
			Optional<Vault.Read> read = vault.readNote(sourceId);
			if (read.isEmpty() || !PalaceAccess.canRead(player, read.get().note())) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.note_unavailable"));
				return;
			}
			NoteSummary sourceNote = read.get().note();
			String body = read.get().body();
			boolean encrypted = sourceNote.encrypted();
			if (encrypted != Ciphertext.isEncrypted(body)) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.encryption_mismatch"));
				return;
			}
			if (encrypted && (!CryptoPayloads.allowed(player) || body.length() > CryptoPayloads.MAX_ACTION_BODY * 4)) {
				player.sendSystemMessage(Component.translatable(CryptoPayloads.allowed(player)
						? "command.memorypalace.crypto_too_long" : "command.memorypalace.crypto_denied", CryptoPayloads.MAX_ACTION_BODY));
				return;
			}
			String file = sourceNote.relativePath().substring(sourceNote.relativePath().lastIndexOf('/') + 1);
			String base = "抄本/" + file.substring(0, file.length() - 3) + " 抄本";
			String name = freeName(vault, player, base);
			// 加密笔记先建一篇空的（默认标题），内容等客户端加密好再存
			Optional<PalaceCommands.Created> created = PalaceCommands.createNote(player, name, encrypted ? "" : body, false,
					player::sendSystemMessage);
			if (created.isEmpty()) {
				return;
			}
			NoteSummary note = created.get().note();
			replace(player, stack, note);
			if (encrypted) {
				// "原笔记ID:原密文"：客户端用自己的钥匙解开原笔记（密文绑定原 ID），再按新笔记的 ID 加密
				CryptoPayloads.sendAction(player, note.id(), "copy_encrypted", note.title(), sourceId + ":" + body);
			} else if (created.get().lockedFolder().isPresent()) {
				// 抄到了被锁的文件夹里：不能先存明文，只建了一篇空的
				player.sendSystemMessage(Component.translatable("message.memorypalace.copy_into_locked_folder"));
				PalaceCommands.openCreated(player, created.get(), false);
			}
			MemoryPalace.LOGGER.info("{} 抄写了笔记 {} → {}", player.getName().getString(), sourceNote.relativePath(), note.relativePath());
		} catch (IOException e) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.read_failed"));
		}
	}

	// "抄本/xx 抄本" 已经有了就试 "xx 抄本 2"、"xx 抄本 3"……
	private static String freeName(Vault vault, ServerPlayer player, String base) {
		String prefix = PalaceAccess.admin(player) ? "" : PalaceCommands.personalFolder(player) + "/";
		for (int i = 1; i <= MAX_TRIES; i++) {
			String name = i == 1 ? base : base + " " + i;
			if (vault.resolve(prefix + name).isEmpty()) {
				return name;
			}
		}
		return base + " " + LocalDateTime.now().format(STAMP);
	}

	/**
	 * 右键待上锁的笔记书：作者右键时让客户端加密这篇。
	 * @return 处理了（发了加密请求，调用方就不再打开笔记）
	 */
	static boolean lockPending(ServerPlayer player, ItemStack stack, UUID id) {
		if (!NoteItems.pendingLock(stack)) {
			return false;
		}
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return false;
		}
		// 先按目录卡片判断：已经加密的去掉标记照常打开；不是作者的照常打开（都不用整篇读）
		Optional<NoteSummary> card = vault.find(id);
		if (card.isEmpty()) {
			return false;
		}
		if (card.get().encrypted()) {
			NoteBooks.refresh(stack, card.get());
			return false;
		}
		if (!NoteAccess.isOwner(card.get().access(), PalaceAccess.who(player)) || !CryptoPayloads.allowed(player)) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.lock_owner_only"));
			return false;
		}
		long now = System.currentTimeMillis();
		String key = player.getUUID() + ":" + id;
		Long sent = LOCK_INFLIGHT.get(key);
		if (sent != null && now - sent < LOCK_INFLIGHT_MILLIS) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.lock_in_progress"));
			return true;
		}
		if (PalaceInteractions.tooLarge(vault, id)) {
			return false; // 太大的不读，照常打开（打开那边会说太大）
		}
		if (tooFast(player)) {
			return true;
		}
		if (LOCK_INFLIGHT.size() > 1000) {
			LOCK_INFLIGHT.values().removeIf(t -> now - t >= LOCK_INFLIGHT_MILLIS);
		}
		try {
			Optional<Vault.Read> read = vault.readNote(id);
			if (read.isEmpty() || read.get().note().encrypted()
					|| !NoteAccess.isOwner(read.get().note().access(), PalaceAccess.who(player))) {
				return false;
			}
			NoteSummary note = read.get().note();
			if (!CryptoPayloads.sendAction(player, id, "encrypt", note.title(), read.get().body())) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
				return false;
			}
			LOCK_INFLIGHT.put(key, now);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
