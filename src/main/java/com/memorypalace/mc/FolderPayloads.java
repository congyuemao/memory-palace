package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文件夹独立密码的消息。服务端只存取锁文件（.palace-lock，里面是用文件夹密码锁住的文件夹钥匙，没有密码），
 * 加密、解密全在客户端。规则：
 * - 谁能锁：玩家只能锁自己个人区 players/&lt;UUID&gt;/ 下面的文件夹（个人区本身不行，里面有收件箱）；
 *   房主（专用服务器上是管理员）能锁个人区以外的文件夹。收件箱不能锁；不能锁在已经锁了的文件夹里面或外面（不嵌套）；
 *   文件夹里的笔记必须都是自己的
 * - 锁文件内容只发给读得了这篇笔记的人（加密笔记只有作者），用来在客户端输入文件夹密码解锁
 * - 已经有锁时，只能换成同一把文件夹钥匙（改密码、换恢复密钥），不能换成另一把：那样用旧钥匙加密的笔记就再也打不开了
 */
public final class FolderPayloads {
	private FolderPayloads() {}

	/** 客户端 → 服务端：我要这篇笔记所在文件夹的锁。 */
	public record LockRequest(UUID noteId) implements CustomPacketPayload {
		public static final Type<LockRequest> TYPE = new Type<>(MemoryPalace.id("folder_lock_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LockRequest> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, LockRequest::noteId, LockRequest::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：锁的内容。status："ok"、"missing"（不在被锁的文件夹里）、"denied"、"error"。 */
	public record LockData(UUID noteId, String status, String folder, String content) implements CustomPacketPayload {
		public static final Type<LockData> TYPE = new Type<>(MemoryPalace.id("folder_lock_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LockData> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, LockData::noteId,
				ByteBufCodecs.stringUtf8(16), LockData::status,
				ByteBufCodecs.stringUtf8(1024), LockData::folder,
				ByteBufCodecs.stringUtf8(FolderLocks.MAX_CHARS), LockData::content,
				LockData::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：写锁文件（第一次上锁时 expectedHash 为 ""）。 */
	public record LockWrite(int requestId, String folder, String expectedHash, String content) implements CustomPacketPayload {
		public static final Type<LockWrite> TYPE = new Type<>(MemoryPalace.id("folder_lock_write"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LockWrite> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, LockWrite::requestId,
				ByteBufCodecs.stringUtf8(1024), LockWrite::folder,
				ByteBufCodecs.stringUtf8(64), LockWrite::expectedHash,
				ByteBufCodecs.stringUtf8(FolderLocks.MAX_CHARS), LockWrite::content,
				LockWrite::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：写锁结果："OK"、"CONFLICT"、"DENIED"、"ERROR"。 */
	public record LockWriteResult(int requestId, String result) implements CustomPacketPayload {
		public static final Type<LockWriteResult> TYPE = new Type<>(MemoryPalace.id("folder_lock_write_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LockWriteResult> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, LockWriteResult::requestId,
				ByteBufCodecs.stringUtf8(16), LockWriteResult::result,
				LockWriteResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：刚锁好这个文件夹，把里面还没用文件夹钥匙加密的笔记逐篇发过来（Action "encrypt_folder"）。 */
	public record EncryptAll(String folder) implements CustomPacketPayload {
		public static final Type<EncryptAll> TYPE = new Type<>(MemoryPalace.id("folder_encrypt_all"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EncryptAll> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(1024), EncryptAll::folder, EncryptAll::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// 读写锁的限速（和钥匙圈一样）
	private static final RateLimiter READ_RATE = new RateLimiter(2, 10);
	private static final RateLimiter WRITE_RATE = new RateLimiter(0.5, 5);

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(LockRequest.TYPE, LockRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LockWrite.TYPE, LockWrite.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EncryptAll.TYPE, EncryptAll.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LockData.TYPE, LockData.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LockWriteResult.TYPE, LockWriteResult.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(LockRequest.TYPE, (p, ctx) -> ServerPlayNetworking.send(ctx.player(), read(ctx.player(), p.noteId())));
		ServerPlayNetworking.registerGlobalReceiver(LockWrite.TYPE,
				(p, ctx) -> ServerPlayNetworking.send(ctx.player(), new LockWriteResult(p.requestId(), write(ctx.player(), p))));
		ServerPlayNetworking.registerGlobalReceiver(EncryptAll.TYPE, (p, ctx) -> encryptAll(ctx.player(), p.folder()));
	}

	/**
	 * 这个人能不能锁 / 改这个文件夹的锁（只看位置，不看里面的笔记）：
	 * 自己个人区下面的子文件夹；或者自己拥有无主笔记（房主、专用服务器的管理员）时，个人区以外的文件夹。收件箱不行。
	 */
	public static boolean mayManage(ServerPlayer player, String folder) {
		if (!CryptoPayloads.allowed(player) || folder.isEmpty() || Inbox.isInboxFolder(folder)) {
			return false;
		}
		if (folder.startsWith(NoteAccess.personalFolder(player.getUUID()) + "/")) {
			return true;
		}
		boolean players = folder.equals(NoteAccess.PLAYERS_FOLDER) || folder.startsWith(NoteAccess.PLAYERS_FOLDER + "/");
		return !players && PalaceAccess.who(player).admin();
	}

	/** 文件夹里（含子文件夹）的笔记。 */
	public static List<NoteSummary> notesIn(Vault vault, String folder) {
		return vault.list().stream()
				.filter(n -> n.folder().equals(folder) || n.folder().startsWith(folder + "/")).toList();
	}

	/** folder 或它下面的任何一级文件夹已经有锁。 */
	public static boolean hasLockInside(Vault vault, String folder) throws IOException {
		var dir = vault.root().resolve(folder);
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		try (Stream<java.nio.file.Path> files = Files.walk(dir)) {
			return files.anyMatch(f -> f.getFileName().toString().equals(FolderLocks.FILE));
		}
	}

	private static LockData read(ServerPlayer player, UUID noteId) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return new LockData(noteId, "error", "", "");
		}
		if (!CryptoPayloads.allowed(player) || !READ_RATE.tryTake(player.getUUID())) {
			return new LockData(noteId, "denied", "", "");
		}
		Optional<NoteSummary> note = vault.find(noteId).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty()) {
			return new LockData(noteId, "denied", "", "");
		}
		try {
			Optional<String> folder = FolderLocks.lockedFolderOf(vault, note.get());
			if (folder.isEmpty()) {
				return new LockData(noteId, "missing", "", "");
			}
			Optional<String> content = FolderLocks.read(vault, folder.get());
			return content.map(c -> new LockData(noteId, "ok", folder.get(), c))
					.orElseGet(() -> new LockData(noteId, "missing", "", ""));
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.error("读取文件夹锁失败", e);
			return new LockData(noteId, "error", "", "");
		}
	}

	private static String write(ServerPlayer player, LockWrite payload) {
		Vault vault = PalaceServer.vault();
		String folder = payload.folder();
		if (vault == null || !mayManage(player, folder)) {
			return "DENIED";
		}
		if (!WRITE_RATE.tryTake(player.getUUID())) {
			return "ERROR";
		}
		try {
			String newId = KeyRing.folderId(payload.content());
			if (newId == null) {
				return "ERROR"; // 不是文件夹钥匙
			}
			Optional<String> current = FolderLocks.read(vault, folder);
			if (current.isPresent()) {
				// 已经有锁：只能换成同一把文件夹钥匙（改密码、换恢复密钥）
				if (!newId.equals(KeyRing.folderId(current.get()))) {
					return "DENIED";
				}
			} else {
				// 第一次上锁：不能嵌套，里面的笔记都得是自己的
				if (FolderLocks.lockedFolder(vault, folder).isPresent() || hasLockInside(vault, folder)) {
					return "DENIED";
				}
				NoteAccess.Who who = PalaceAccess.who(player);
				if (notesIn(vault, folder).stream().anyMatch(n -> !NoteAccess.isOwner(n.access(), who))) {
					return "DENIED";
				}
			}
			return switch (FolderLocks.write(vault, folder, payload.expectedHash(), payload.content())) {
				case OK -> "OK";
				case CONFLICT -> "CONFLICT";
				case MISSING_FOLDER -> "ERROR";
			};
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.warn("写文件夹锁失败：{}", folder, e);
			return "ERROR";
		}
	}

	/** 刚锁好的文件夹：把里面还没加上这把锁的笔记逐篇发给客户端加密（明文、旧格式 MPAL1 都发）。 */
	private static void encryptAll(ServerPlayer player, String folder) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !mayManage(player, folder) || !WRITE_RATE.tryTake(player.getUUID())) {
			return;
		}
		try {
			Optional<String> content = FolderLocks.read(vault, folder);
			if (content.isEmpty()) {
				return;
			}
			NoteEnvelope.Lock lock = new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, KeyRing.folderId(content.get()));
			NoteAccess.Who who = PalaceAccess.who(player);
			int tooLong = 0;
			for (NoteSummary note : notesIn(vault, folder)) {
				if (!NoteAccess.isOwner(note.access(), who)) {
					continue;
				}
				Optional<Vault.Read> read = vault.readNote(note.id());
				if (read.isEmpty()) {
					continue;
				}
				String body = read.get().body();
				if (NoteEnvelope.isEnvelope(body) && NoteEnvelope.locks(body).contains(lock)) {
					continue; // 已经用这把文件夹钥匙加密过了
				}
				if (!CryptoPayloads.sendAction(player, note.id(), "encrypt_folder", note.title(), body)) {
					tooLong++;
				}
			}
			if (tooLong > 0) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.folder_too_long", tooLong, CryptoPayloads.MAX_ACTION_BODY));
			}
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.error("加密文件夹里的笔记失败：{}", folder, e);
		}
	}
}
