package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.Ciphertext;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * 加密相关的消息。服务端只做两件事：存取钥匙圈文件（里面是"锁着的箱子"，没有密码），
 * 以及把"加密/解密这篇笔记"的请求转给客户端——真正的加密解密全在客户端做，服务端从不接触明文和密码。
 */
public final class CryptoPayloads {
	private static final int MAX_KEYRING = 4096;
	/** 加密/解密时正文要整个装进一个消息，所以加密功能只支持这么长以内的笔记（约 20 万字，日记足够）。 */
	public static final int MAX_ACTION_BODY = 200_000;

	private CryptoPayloads() {}

	/**
	 * 能不能用加密：每人一个钥匙圈（见 file），所以多人开着时谁都能用，但只碰得到自己的钥匙圈；
	 * 加密只能加密自己是作者的笔记（命令和 EditPayloads 里另外检查）。多人关着时只有本机房主能用。
	 */
	public static boolean allowed(ServerPlayer player) {
		return PalaceAccess.enabled(player);
	}

	// 钥匙圈限速：读每人每秒 2 次、最多连着 10 次（每次解锁都要读一次）；
	// 写每 2 秒 1 次、最多连着 5 次（设密码、改密码、生成恢复密钥都远到不了）
	private static final RateLimiter KEYRING_READ_RATE = new RateLimiter(2, 10);
	private static final RateLimiter KEYRING_WRITE_RATE = new RateLimiter(0.5, 5);

	/**
	 * 客户端 → 服务端：我要解除这篇的加密（玩家在打开着的加密笔记上点了 🔓）。
	 * 服务端回一个 Action("decrypt")，带上它存着的密文；客户端只接受和自己刚打开的那份密文一致的回复。
	 */
	public record DecryptRequest(UUID noteId) implements CustomPacketPayload {
		public static final Type<DecryptRequest> TYPE = new Type<>(MemoryPalace.id("decrypt_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DecryptRequest> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, DecryptRequest::noteId, DecryptRequest::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：我要钥匙圈。 */
	public record KeyringRequest() implements CustomPacketPayload {
		public static final KeyringRequest INSTANCE = new KeyringRequest();
		public static final Type<KeyringRequest> TYPE = new Type<>(MemoryPalace.id("keyring_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KeyringRequest> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端 → 客户端：钥匙圈。status：
	 * "ok"（content 是内容）、"missing"（还没启用过加密）、"error"（读不出来——绝不能当成"还没启用"去覆盖它）、
	 * "denied"（不是本机房主）。
	 */
	public record KeyringData(String status, String content) implements CustomPacketPayload {
		public static final Type<KeyringData> TYPE = new Type<>(MemoryPalace.id("keyring_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KeyringData> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(16), KeyringData::status,
				ByteBufCodecs.stringUtf8(MAX_KEYRING), KeyringData::content,
				KeyringData::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端 → 服务端：保存新的钥匙圈。expectedHash 是客户端以为的"当前内容"的指纹（还没有钥匙圈时为 ""），
	 * 和磁盘上的对不上就拒绝，防止过期的写入覆盖新的钥匙圈。
	 */
	public record KeyringWrite(int requestId, String expectedHash, String content) implements CustomPacketPayload {
		public static final Type<KeyringWrite> TYPE = new Type<>(MemoryPalace.id("keyring_write"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KeyringWrite> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, KeyringWrite::requestId,
				ByteBufCodecs.stringUtf8(64), KeyringWrite::expectedHash,
				ByteBufCodecs.stringUtf8(MAX_KEYRING), KeyringWrite::content,
				KeyringWrite::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：钥匙圈写入结果："OK"、"CONFLICT"、"ERROR"、"DENIED"。只有 OK 才算真的存好了。 */
	public record KeyringWriteResult(int requestId, String result) implements CustomPacketPayload {
		public static final Type<KeyringWriteResult> TYPE = new Type<>(MemoryPalace.id("keyring_write_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KeyringWriteResult> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, KeyringWriteResult::requestId,
				ByteBufCodecs.stringUtf8(16), KeyringWriteResult::result,
				KeyringWriteResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端 → 客户端：请对这篇笔记做 action。body 是服务端存着的正文（明文或密文），客户端据此加密/解密后再保存回来。
	 * action："encrypt" 加密、"encrypt_new" 加密后打开编辑器（新建加密日记）、"decrypt" 解除加密、"lock" 立刻上锁（noteId 无意义）。
	 */
	public record Action(UUID noteId, String action, String title, String body) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(MemoryPalace.id("crypto_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Action::noteId,
				ByteBufCodecs.stringUtf8(32), Action::action,
				ByteBufCodecs.stringUtf8(OpenNotePayload.MAX_TITLE), Action::title,
				// 密文最多约是明文的 4 倍（中文 3 字节 → Base64 再 ×4/3）
				ByteBufCodecs.stringUtf8(MAX_ACTION_BODY * 4 + 64), Action::body,
				Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(KeyringRequest.TYPE, KeyringRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(KeyringWrite.TYPE, KeyringWrite.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DecryptRequest.TYPE, DecryptRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(KeyringData.TYPE, KeyringData.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(KeyringWriteResult.TYPE, KeyringWriteResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Action.TYPE, Action.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(KeyringRequest.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!allowed(player)) {
				ServerPlayNetworking.send(player, new KeyringData("denied", ""));
				return;
			}
			if (!KEYRING_READ_RATE.tryTake(player.getUUID())) {
				ServerPlayNetworking.send(player, new KeyringData("error", ""));
				return;
			}
			Path file = file(player);
			if (file == null) {
				ServerPlayNetworking.send(player, new KeyringData("error", ""));
				return;
			}
			try {
				ServerPlayNetworking.send(player, new KeyringData("ok", Files.readString(file, StandardCharsets.UTF_8)));
			} catch (NoSuchFileException e) {
				ServerPlayNetworking.send(player, new KeyringData("missing", ""));
			} catch (IOException e) {
				MemoryPalace.LOGGER.error("读取钥匙圈失败", e);
				ServerPlayNetworking.send(player, new KeyringData("error", ""));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(KeyringWrite.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ServerPlayNetworking.send(player, new KeyringWriteResult(payload.requestId(), write(player, payload)));
		});
		ServerPlayNetworking.registerGlobalReceiver(DecryptRequest.TYPE, (payload, context) -> requestDecrypt(context.player(), payload.noteId()));
	}

	/** 把加密笔记的密文发给客户端去解除加密：只有本机房主、能改这篇、它确实是加密笔记时才发。 */
	private static void requestDecrypt(ServerPlayer player, UUID noteId) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !allowed(player)) {
			player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_denied"));
			return;
		}
		try {
			Optional<Vault.Read> read = vault.readNote(noteId);
			if (read.isEmpty() || !PalaceAccess.canEdit(player, read.get().note())) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.note_unavailable"));
				return;
			}
			NoteSummary note = read.get().note();
			if (!note.encrypted() || !Ciphertext.isEncrypted(read.get().body())) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.not_encrypted", note.title()));
				return;
			}
			if (!sendAction(player, noteId, "decrypt", note.title(), read.get().body())) {
				player.sendSystemMessage(Component.translatable("command.memorypalace.crypto_too_long", MAX_ACTION_BODY));
			}
		} catch (IOException e) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.read_failed"));
		}
	}

	private static String write(ServerPlayer player, KeyringWrite payload) {
		if (!allowed(player)) {
			MemoryPalace.LOGGER.warn("拒绝了 {} 写钥匙圈的请求：多人功能没有开启", player.getName().getString());
			return "DENIED";
		}
		if (!KEYRING_WRITE_RATE.tryTake(player.getUUID())) {
			return "ERROR";
		}
		Path file = file(player);
		if (file == null) {
			return "ERROR";
		}
		try {
			// 先确认磁盘上的就是客户端以为的那一份，再覆盖
			String current;
			try {
				current = Files.readString(file, StandardCharsets.UTF_8);
			} catch (NoSuchFileException e) {
				current = null;
			}
			String currentHash = current == null ? "" : Vault.hash(current);
			if (!currentHash.equals(payload.expectedHash())) {
				return "CONFLICT";
			}
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
			try {
				Files.writeString(tmp, payload.content(), StandardCharsets.UTF_8);
				try {
					Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
			return "OK";
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("保存钥匙圈失败", e);
			return "ERROR";
		}
	}

	/** 把正文发给客户端做加密/解密。正文太长时不发（绝不截断——截断的密文解不开，截断的明文加密后就丢了后半段）。 */
	public static boolean sendAction(ServerPlayer player, UUID noteId, String action, String title, String body) {
		if (body.length() > MAX_ACTION_BODY * 4) {
			return false;
		}
		ServerPlayNetworking.send(player, new Action(noteId, action, OpenNotePayload.clip(title, OpenNotePayload.MAX_TITLE), body));
		return true;
	}

	/**
	 * 这个玩家的钥匙圈文件。本机房主用原来的 .palace/keyring（单人时代的钥匙圈，不迁移）；
	 * 其他玩家各用 .palace/keyrings/&lt;UUID&gt;。每人只读写自己那一份：钥匙圈里是锁着的主密钥，没有密码，
	 * 但也不能让别人拿去离线猜密码，或者覆盖掉。
	 */
	private static Path file(ServerPlayer player) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			return null;
		}
		Path palace = vault.root().resolve(".palace");
		return PalaceAccess.isHost(player) ? palace.resolve("keyring") : palace.resolve("keyrings").resolve(player.getUUID().toString());
	}
}
