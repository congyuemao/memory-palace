package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.vault.FolderLocks;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏内编辑器保存笔记：客户端把正文分块发给服务端（客户端→服务端的包上限只有约 32KB），
 * 服务端收齐后写文件，回一个结果。
 */
public final class EditPayloads {
	/** 每块最多这么多字符：中文 3 字节/字，8000 字 ≈ 24KB，在 32KB 上限以内。 */
	public static final int CHUNK_CHARS = 8000;
	// 512 块 × 8000 字符 ≈ 400 万字符：加密后的长日记（密文约是明文的 4 倍）也放得下
	private static final int MAX_CHUNKS = 512;
	/** expectedHash 以它开头表示"这是一次解除加密"：只有这种保存才允许把加密笔记写成明文。 */
	public static final String DECRYPT_PREFIX = "decrypt:";
	/** expectedHash 以它开头表示"这是一次还原历史版本"：还原前的样子一定留一份历史（可以反悔）。 */
	public static final String RESTORE_PREFIX = "restore:";

	private EditPayloads() {}

	/**
	 * 客户端 → 服务端：正文的第 index 块（共 total 块）。
	 * expectedHash 是编辑器打开时旧正文的指纹，"" 表示强制覆盖；前面加 DECRYPT_PREFIX 表示解除加密。
	 * session 区分同一个玩家的不同次保存。
	 */
	public record SaveChunk(UUID noteId, int session, int index, int total, String expectedHash, String part)
			implements CustomPacketPayload {
		public static final Type<SaveChunk> TYPE = new Type<>(MemoryPalace.id("save_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveChunk> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SaveChunk::noteId,
				ByteBufCodecs.VAR_INT, SaveChunk::session,
				ByteBufCodecs.VAR_INT, SaveChunk::index,
				ByteBufCodecs.VAR_INT, SaveChunk::total,
				ByteBufCodecs.stringUtf8(80), SaveChunk::expectedHash,
				ByteBufCodecs.stringUtf8(CHUNK_CHARS), SaveChunk::part,
				SaveChunk::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：保存结果。result 是 Vault.WriteResult 的名字，或 "ERROR"。newHash 是保存后正文的指纹。 */
	public record SaveResult(UUID noteId, int session, String result, String newHash) implements CustomPacketPayload {
		public static final Type<SaveResult> TYPE = new Type<>(MemoryPalace.id("save_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveResult> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SaveResult::noteId,
				ByteBufCodecs.VAR_INT, SaveResult::session,
				ByteBufCodecs.stringUtf8(32), SaveResult::result,
				ByteBufCodecs.stringUtf8(64), SaveResult::newHash,
				SaveResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：直接打开游戏内编辑器（/palace new 新建笔记之后）。 */
	public record OpenEditor(UUID noteId, String title, String body) implements CustomPacketPayload {
		public static final Type<OpenEditor> TYPE = new Type<>(MemoryPalace.id("open_editor"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenEditor> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, OpenEditor::noteId,
				ByteBufCodecs.stringUtf8(OpenNotePayload.MAX_TITLE), OpenEditor::title,
				ByteBufCodecs.stringUtf8(OpenNotePayload.MAX_BODY), OpenEditor::body,
				OpenEditor::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// 正在接收的保存：玩家 UUID → 已收到的块
	private record Assembly(UUID noteId, int session, String expectedHash, String[] parts) {}

	private static final Map<UUID, Assembly> PENDING = new ConcurrentHashMap<>();

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(SaveChunk.TYPE, SaveChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SaveResult.TYPE, SaveResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenEditor.TYPE, OpenEditor.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SaveChunk.TYPE, (chunk, context) -> receive(context.player(), chunk));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PENDING.remove(handler.getPlayer().getUUID());
			SAVE_RATE.forget(handler.getPlayer().getUUID());
		});
	}

	private static void receive(ServerPlayer player, SaveChunk chunk) {
		// 笔记 ID 不是权限凭证：知道 ID 也不能覆盖别人的笔记。每一块都查，没权限的连缓存都不留
		Vault current = PalaceServer.vault();
		if (current == null || current.find(chunk.noteId()).filter(note -> PalaceAccess.canEdit(player, note)).isEmpty()) {
			PENDING.remove(player.getUUID());
			reply(player, chunk, "DENIED", "");
			return;
		}
		if (chunk.total() < 1 || chunk.total() > MAX_CHUNKS || chunk.index() < 0 || chunk.index() >= chunk.total()) {
			reply(player, chunk, "ERROR", "");
			return;
		}
		// 普通玩家：块数不能超过字数上限需要的块数（每人同时只有一份在收，最多占这么多内存），挡住"发几百块、永远不发最后一块"
		int maxChars = PalaceConfig.get().maxNoteChars();
		boolean admin = PalaceAccess.admin(player);
		// （客户端为了不切开 emoji，一块可能少 1 个字，所以按 CHUNK_CHARS - 1 算）
		if (!admin && maxChars > 0 && chunk.total() > maxChars / (CHUNK_CHARS - 1) + 1) {
			PENDING.remove(player.getUUID());
			reply(player, chunk, "TOO_LONG", "");
			return;
		}
		Assembly a = PENDING.get(player.getUUID());
		// 新的一次保存（或者上次没发完就又保存了）：重新开始收
		if (a == null || a.session() != chunk.session() || !a.noteId().equals(chunk.noteId()) || a.parts().length != chunk.total()) {
			// 保存限速：每人每秒 1 次、最多连着 10 次（手动保存远到不了）。在开始收的时候就扣，
			// 不停换 session 的小包也会被挡住，不会一直重新分配缓冲区
			if (!SAVE_RATE.tryTake(player.getUUID())) {
				PENDING.remove(player.getUUID());
				reply(player, chunk, "BUSY", "");
				return;
			}
			a = new Assembly(chunk.noteId(), chunk.session(), chunk.expectedHash(), new String[chunk.total()]);
			PENDING.put(player.getUUID(), a);
		}
		a.parts()[chunk.index()] = chunk.part();
		for (String part : a.parts()) {
			if (part == null) {
				return; // 还没收齐
			}
		}
		PENDING.remove(player.getUUID());
		String body = String.join("", a.parts());
		// 普通玩家一篇最多多少字（配置文件 max_note_chars），挡住把服务器硬盘写满；管理员不限
		if (maxChars > 0 && body.length() > maxChars && !admin) {
			reply(player, chunk, "TOO_LONG", "");
			return;
		}

		Vault vault = PalaceServer.vault();
		if (vault == null) {
			reply(player, chunk, "ERROR", "");
			return;
		}
		String guard = a.expectedHash();
		boolean restore = guard.startsWith(RESTORE_PREFIX);
		if (restore) {
			guard = guard.substring(RESTORE_PREFIX.length());
		}
		boolean decrypt = guard.startsWith(DECRYPT_PREFIX);
		if (decrypt) {
			guard = guard.substring(DECRYPT_PREFIX.length());
		}
		// 加密笔记只能通过"解除加密"变回明文（要能用加密功能）；普通保存写入明文会被拒绝，
		// 防止热重载等情况下界面状态不对，把解密后的内容悄悄以明文存回去
		boolean wasEncrypted = vault.find(chunk.noteId()).map(NoteSummary::encrypted).orElse(false);
		if (wasEncrypted && !Ciphertext.isEncrypted(body) && (!decrypt || !CryptoPayloads.allowed(player))) {
			reply(player, chunk, "REFUSED", "");
			return;
		}
		// 新格式密文：格式要对，阅读密码锁最多一把（核对阅读密码时只认一把，多出来的锁谁也说不清是哪个密码）
		if (NoteEnvelope.isEnvelope(body)) {
			try {
				if (NoteEnvelope.passwordLockCount(body) > 1) {
					reply(player, chunk, "ERROR", "");
					return;
				}
			} catch (IllegalArgumentException e) {
				reply(player, chunk, "ERROR", "");
				return;
			}
		}
		// 写入前按文件此刻的内容再判断一次：权限、加密状态都可能在收块期间被外部改了
		// 加密状态只能这样变：
		// - 密文 → 明文：只有"解除加密"，而且是作者本人（加密笔记只有作者能改）
		// - 明文 → 密文：只有作者用自己的钥匙圈加密自己的笔记（替别人加密，别人就再也打不开。
		//   被分享了编辑权的人也不能靠伪造一段 MPAL1: 开头的正文把笔记"加密"、把其他编辑者挡在外面）
		boolean mayCrypt = CryptoPayloads.allowed(player);
		boolean mayDecrypt = decrypt && mayCrypt;
		boolean bodyEncrypted = Ciphertext.isEncrypted(body);
		NoteAccess.Who who = PalaceAccess.who(player);
		try {
			// 被锁的文件夹（文件夹独立密码）里的笔记只能存成密文：防止客户端出错或被骗，把内容以明文存进去
			Vault.WriteResult result = vault.writeBody(chunk.noteId(), body, guard.isEmpty() ? null : guard,
					note -> note.canEdit(who) && (note.encrypted()
							? bodyEncrypted || mayDecrypt
							: !bodyEncrypted || mayCrypt && NoteAccess.isOwner(note.access(), who))
							&& (bodyEncrypted || mayDecrypt || FolderLocks.lockedFolderOf(vault, note).isEmpty()),
					restore);
			reply(player, chunk, result.name(), result == Vault.WriteResult.OK ? Vault.hash(body) : "");
			if (result == Vault.WriteResult.OK) {
				NoteItemUse.afterSave(player, chunk.noteId(), body, guard); // 抄进被锁文件夹的抄本：存好了才换成笔记书
				// 加密了、改了标题：背包里的书马上换贴图、书名
				vault.find(chunk.noteId()).ifPresent(note -> NoteBooks.refreshCarried(player.level().getServer(), note));
			}
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.error("保存笔记 {} 失败", chunk.noteId(), e);
			reply(player, chunk, "ERROR", "");
		}
	}

	private static final RateLimiter SAVE_RATE = new RateLimiter(1, 10);

	private static void reply(ServerPlayer player, SaveChunk chunk, String result, String newHash) {
		ServerPlayNetworking.send(player, new SaveResult(chunk.noteId(), chunk.session(), result, newHash));
	}
}
