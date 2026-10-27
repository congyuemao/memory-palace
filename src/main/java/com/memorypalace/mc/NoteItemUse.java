package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.vault.CopyJobs;
import com.memorypalace.core.vault.FolderCopy;
import com.memorypalace.core.vault.FolderLocks;
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
			if (encrypted && !CryptoPayloads.allowed(player)) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_denied"));
				return;
			}
			// 上次抄进被锁文件夹没抄完（取消了、保存失败）：按服务端自己的记录接着抄，或者抄好了就换成笔记书。
			// 物品上只有记录 ID（可以伪造）：查不到、不是自己的、原笔记对不上的一律当没有，另建一篇
			Optional<UUID> jobId = NoteItems.copyJob(stack);
			CopyJobs jobs = PalaceServer.copyJobs();
			if (jobId.isPresent()) {
				Optional<CopyJobs.Job> job = jobs == null ? Optional.empty() : jobs.find(jobId.get(), player.getUUID());
				if (job.isPresent() && !job.get().source().equals(sourceId)) {
					// 记录在，原笔记却对不上（物品被改过）：记录也作废，删不掉就先别另建
					try {
						jobs.finish(job.get().id());
					} catch (IOException e) {
						player.sendSystemMessage(Component.translatable("message.memorypalace.read_failed"));
						return;
					}
					job = Optional.empty();
				}
				if (job.isEmpty()) {
					NoteItems.clearCopyJob(stack);
				} else if (continueCopy(player, stack, vault, job.get(), body, encrypted)) {
					return;
				}
			}
			// 要整篇发给客户端的（加密笔记、抄进被锁文件夹的）才受消息大小限制；普通文件夹里的明文由服务端直接复制
			boolean viaClient = encrypted || targetLocked(vault, player);
			if (viaClient && body.length() > CryptoPayloads.MAX_ACTION_BODY * 4) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
				return;
			}
			String file = sourceNote.relativePath().substring(sourceNote.relativePath().lastIndexOf('/') + 1);
			String base = "抄本/" + file.substring(0, file.length() - 3) + " 抄本";
			String name = freeName(vault, player, base);
			// 加密笔记先建一篇空的（默认标题），内容等客户端加密好再存；被锁的文件夹里 createNote 也只建空的
			Optional<PalaceCommands.Created> created = PalaceCommands.createNote(player, name, encrypted ? "" : body, false,
					player::sendSystemMessage);
			if (created.isEmpty()) {
				return;
			}
			NoteSummary note = created.get().note();
			if (created.get().lockedFolder().isPresent()) {
				// 抄进了被锁的文件夹：不能先存明文。物品先不换，记下目标；原正文交给客户端用文件夹钥匙加密后存进去，
				// 存好了（afterSave）才换成笔记书、说抄好了
				Optional<String> lockId = currentLockId(vault, note);
				if (!CryptoPayloads.allowed(player) || jobs == null || lockId.isEmpty()) {
					player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_denied"));
					return;
				}
				String placeholderHash = Vault.hash(created.get().body());
				CopyJobs.Job job = jobs.start(player.getUUID(), sourceId, note.id(), placeholderHash, lockId.get());
				NoteItems.setCopyJob(stack, job.id());
				sendFolderCopy(player, note, sourceId, placeholderHash, body);
			} else {
				replace(player, stack, note);
				if (encrypted) {
					// "原笔记ID:原密文"：客户端用自己的钥匙解开原笔记（密文绑定原 ID），再按新笔记的 ID 加密
					CryptoPayloads.sendAction(player, note.id(), "copy_encrypted", note.title(), sourceId + ":" + body);
				}
			}
			MemoryPalace.LOGGER.info("{} 抄写了笔记 {} → {}", player.getName().getString(), sourceNote.relativePath(), note.relativePath());
		} catch (IOException e) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.read_failed"));
		}
	}

	/** 把原笔记正文发给客户端，让它用目标文件夹的钥匙按目标 ID 加密后存进去（保存时核对占位正文的指纹）。 */
	private static void sendFolderCopy(ServerPlayer player, NoteSummary target, UUID sourceId, String placeholderHash, String body) {
		if (CryptoPayloads.sendAction(player, target.id(), "copy_into_folder", target.title(), FolderCopy.pack(sourceId, placeholderHash, body))) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.copy_encrypting", target.relativePath()));
		} else {
			player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_too_long", CryptoPayloads.MAX_ACTION_BODY));
		}
	}

	/**
	 * 按服务端的抄写记录接着做。@return 处理了（换了笔记书，或者又发了一次加密请求）；false = 这条记录作废了，调用方另建一篇
	 */
	private static boolean continueCopy(ServerPlayer player, ItemStack stack, Vault vault, CopyJobs.Job job, String body,
			boolean encrypted) throws IOException {
		Optional<Vault.Read> target = vault.readNote(job.target());
		boolean owned = target.isPresent() && NoteAccess.isOwner(target.get().note().access(), PalaceAccess.who(player));
		Optional<String> lockNow = target.flatMap(t -> currentLockId(vault, t.note()));
		FolderCopy.Next next = FolderCopy.next(target, job.placeholderHash(), owned, job.lockId());
		if (next == FolderCopy.Next.FINISH && lockNow.filter(job.lockId()::equals).isPresent()) {
			finishCopy(player, stack, target.get().note(), job);
			return true;
		}
		if (next == FolderCopy.Next.RESEND) {
			if (lockNow.isPresent()) {
				// 文件夹还锁着（而且是建目标时那把锁）：只能让客户端加密。Vault.writeBody 自己不管文件夹锁，绝不能在这里写明文
				if (lockNow.get().equals(job.lockId())) {
					if (!CryptoPayloads.allowed(player)) {
						player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_denied"));
						return true;
					}
					sendFolderCopy(player, target.get().note(), job.source(), job.placeholderHash(), body);
					return true;
				}
			} else if (!encrypted && vault.writeBody(job.target(), body, job.placeholderHash(),
					n -> NoteAccess.isOwner(n.access(), PalaceAccess.who(player)) && FolderLocks.lockedFolderOf(vault, n).isEmpty())
					== Vault.WriteResult.OK) {
				// 文件夹的密码已经解除了：不用加密，直接把正文抄进去（只在它还是占位的样子、还没上锁时写）
				Optional<NoteSummary> written = vault.find(job.target());
				if (written.isPresent()) {
					finishCopy(player, stack, written.get(), job);
					return true;
				}
			}
		} else if (target.isPresent() && owned) {
			// 目标被外部改过（或者换了锁）：不动它，另建一篇
			player.sendSystemMessage(Component.translatable("message.memorypalace.copy_target_changed"));
		}
		PalaceServer.copyJobs().finish(job.id());
		NoteItems.clearCopyJob(stack);
		return false;
	}

	/** 这篇笔记所在的被锁文件夹现在的锁 ID；不在被锁的文件夹里、读不出锁 → empty。 */
	private static Optional<String> currentLockId(Vault vault, NoteSummary note) {
		try {
			Optional<String> folder = FolderLocks.lockedFolderOf(vault, note);
			if (folder.isEmpty()) {
				return Optional.empty();
			}
			return FolderLocks.read(vault, folder.get()).map(KeyRing::folderId);
		} catch (IOException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** 抄本会建在哪个文件夹（"抄本/"，普通玩家在自己的个人区里）现在是不是上了锁。 */
	private static boolean targetLocked(Vault vault, ServerPlayer player) {
		String folder = (PalaceAccess.admin(player) ? "" : PalaceCommands.personalFolder(player) + "/") + "抄本";
		try {
			return FolderLocks.lockedFolder(vault, folder).isPresent();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * 抄好了：先删掉记录（删不掉就什么都不给，提示重试——记录还在的话同一条记录还能再换一次书），
	 * 再把抄本用掉一本、换成目标笔记的笔记书；同一叠剩下的不再记着这条记录。
	 */
	private static void finishCopy(ServerPlayer player, ItemStack stack, NoteSummary target, CopyJobs.Job job) {
		try {
			PalaceServer.copyJobs().finish(job.id());
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("删除抄写记录失败", e);
			player.sendSystemMessage(Component.translatable("message.memorypalace.copy_retry"));
			return;
		}
		stack.consume(1, player);
		NoteItems.clearCopyJob(stack);
		player.getInventory().placeItemBackInInventory(NoteBooks.create(target), Prediction.SERVER_ONLY);
		player.sendSystemMessage(Component.translatable("message.memorypalace.copy_done", target.relativePath()));
	}

	/**
	 * 一篇笔记保存成功之后（EditPayloads 调用）：只有这三样都对得上才算抄好——
	 * 服务端记着这个人正往这篇里抄、这次保存之前的正文正是登记的占位（previousHash）、存进去的是只有登记那把文件夹锁的密文，
	 * 而且那把锁就是文件夹现在的锁。然后在这个人身上找到那本抄本换成笔记书；没找到（扔了、放箱子里了）就等下次右键它时补上。
	 */
	static void afterSave(ServerPlayer player, UUID noteId, String body, String previousHash) {
		CopyJobs jobs = PalaceServer.copyJobs();
		Vault vault = PalaceServer.vault();
		if (jobs == null || vault == null || previousHash == null || previousHash.isEmpty()) {
			return;
		}
		try {
			Optional<CopyJobs.Job> job = jobs.byTarget(noteId, player.getUUID());
			if (job.isEmpty() || !job.get().placeholderHash().equals(previousHash) || !FolderCopy.sealedWith(body, job.get().lockId())) {
				return;
			}
			Optional<NoteSummary> note = vault.find(noteId);
			if (note.isEmpty() || currentLockId(vault, note.get()).filter(job.get().lockId()::equals).isEmpty()) {
				return;
			}
			var inventory = player.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				if (NoteItems.copyJob(stack).filter(job.get().id()::equals).isPresent()) {
					finishCopy(player, stack, note.get(), job.get());
					return;
				}
			}
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("读取抄写记录失败", e);
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
