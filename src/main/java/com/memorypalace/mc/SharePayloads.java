package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.PasswordThrottle;
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
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 单篇阅读密码：作者给加密笔记加一把 p 锁（见 NoteEnvelope），把密码告诉朋友，朋友就能打开这一篇（只读）。
 * 流程：
 * 1. 读不了这篇的人右键它的书：服务端发来"题目"（盐和参数，不含验证码的哈希）
 * 2. 对方客户端用密码算出验证码发回来；服务端限速核对（每人每篇一分钟错 5 次，之后等待逐次翻倍）
 * 3. 对了才把密文发过去，对方客户端用同一个密码算出的包装钥匙解开
 * 服务端从头到尾看不到密码和明文。不记"谁通过了"：每次打开都要核对一次（对方客户端会在内存里记住密码算出的钥匙，
 * 上锁、离开世界时清掉），作者换密码或取消后旧的验证码自然失效。
 */
public final class SharePayloads {
	private static final int MAX_CHALLENGE = 128;
	// 一篇最多这么长才发（和客户端收正文的上限一致：32 块 × 10 万字）
	private static final int MAX_BODY = 32 * OpenNotePayload.CHUNK_CHARS;

	private SharePayloads() {}

	/** 服务端 → 客户端：这篇要阅读密码，题目是 challenge。 */
	public record PasswordPrompt(UUID noteId, String challenge) implements CustomPacketPayload {
		public static final Type<PasswordPrompt> TYPE = new Type<>(MemoryPalace.id("read_password_prompt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PasswordPrompt> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, PasswordPrompt::noteId,
				ByteBufCodecs.stringUtf8(MAX_CHALLENGE), PasswordPrompt::challenge,
				PasswordPrompt::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：按 challenge 这道题算出的验证码（Base64，32 字节）。 */
	public record PasswordAttempt(UUID noteId, String challenge, String verifier) implements CustomPacketPayload {
		public static final Type<PasswordAttempt> TYPE = new Type<>(MemoryPalace.id("read_password_attempt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PasswordAttempt> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, PasswordAttempt::noteId,
				ByteBufCodecs.stringUtf8(MAX_CHALLENGE), PasswordAttempt::challenge,
				ByteBufCodecs.stringUtf8(64), PasswordAttempt::verifier,
				PasswordAttempt::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端 → 客户端：核对结果。result：
	 * "OK"（接着发正文）、"WRONG"（密码不对）、"WAIT"（错太多次，waitSeconds 秒后再试）、
	 * "CHANGED"（这篇的阅读密码刚换了或取消了，题目对不上）、"UNAVAILABLE"（读不了 / 不存在，说法一样）。
	 */
	public record PasswordResult(UUID noteId, String result, int waitSeconds) implements CustomPacketPayload {
		public static final Type<PasswordResult> TYPE = new Type<>(MemoryPalace.id("read_password_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PasswordResult> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, PasswordResult::noteId,
				ByteBufCodecs.stringUtf8(16), PasswordResult::result,
				ByteBufCodecs.VAR_INT, PasswordResult::waitSeconds,
				PasswordResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private record Attempter(UUID player, UUID note) {}

	private static final PasswordThrottle<Attempter> THROTTLE = new PasswordThrottle<>();
	// 不管试哪篇，每人每 2 秒 1 次、最多连着 5 次：挡住换着笔记刷
	private static final RateLimiter ATTEMPT_RATE = new RateLimiter(0.5, 5);

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PasswordPrompt.TYPE, PasswordPrompt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PasswordAttempt.TYPE, PasswordAttempt.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PasswordResult.TYPE, PasswordResult.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PasswordAttempt.TYPE, (payload, context) -> attempt(context.player(), payload));
		// 只清这个限速；猜错的记录（THROTTLE）不随下线清空，否则重进服务器就能绕过
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ATTEMPT_RATE.forget(handler.getPlayer().getUUID()));
	}

	/** 设了阅读密码的笔记：返回它的密码锁（每篇只该有一把）。不是、格式不对、收件箱 → empty。 */
	static Optional<NoteEnvelope.Lock> passwordLock(NoteSummary note, String body) {
		if (!note.encrypted() || note.inbox() || !NoteEnvelope.isEnvelope(body)) {
			return Optional.empty();
		}
		try {
			return NoteEnvelope.passwordLockCount(body) == 1 ? NoteEnvelope.passwordLock(body) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * 这个人读不了这篇时调用（右键书、打开笔记）：如果这篇设了阅读密码，就发题目让对方输密码。
	 * @return 发了题目（调用方就不要再说"读不了"）
	 */
	static boolean offer(ServerPlayer player, UUID noteId) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player) || PalaceInteractions.tooLarge(vault, noteId)) {
			return false;
		}
		try {
			Optional<Vault.Read> read = vault.readNote(noteId);
			if (read.isEmpty()) {
				return false;
			}
			Optional<NoteEnvelope.Lock> lock = passwordLock(read.get().note(), read.get().body());
			if (lock.isEmpty()) {
				return false;
			}
			ServerPlayNetworking.send(player, new PasswordPrompt(noteId, NoteEnvelope.passwordChallenge(lock.get())));
			return true;
		} catch (IOException | IllegalArgumentException e) {
			return false;
		}
	}

	private static void attempt(ServerPlayer player, PasswordAttempt payload) {
		UUID noteId = payload.noteId();
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player)) {
			reply(player, noteId, "UNAVAILABLE", 0);
			return;
		}
		if (!ATTEMPT_RATE.tryTake(player.getUUID())) {
			reply(player, noteId, "WAIT", 2);
			return;
		}
		Attempter who = new Attempter(player.getUUID(), noteId);
		long wait = THROTTLE.waitSeconds(who);
		if (wait > 0) {
			reply(player, noteId, "WAIT", (int) Math.min(wait, Integer.MAX_VALUE));
			return;
		}
		// 和正常打开一样，太大的文件不读（说法和不存在一样）
		if (PalaceInteractions.tooLarge(vault, noteId)) {
			reply(player, noteId, "UNAVAILABLE", 0);
			return;
		}
		try {
			// 核对和发送用同一次读到的正文：核对完之后作者换了密码，也不会把新密文发给只知道旧密码的人
			Optional<Vault.Read> read = vault.readNote(noteId);
			Optional<NoteEnvelope.Lock> lock = read.flatMap(r -> passwordLock(r.note(), r.body()));
			if (lock.isEmpty()) {
				reply(player, noteId, "UNAVAILABLE", 0);
				return;
			}
			if (!NoteEnvelope.passwordChallenge(lock.get()).equals(payload.challenge())) {
				reply(player, noteId, "CHANGED", 0);
				return;
			}
			if (!NoteEnvelope.checkPassword(lock.get(), decode(payload.verifier()))) {
				THROTTLE.failure(who);
				reply(player, noteId, "WRONG", (int) Math.min(THROTTLE.waitSeconds(who), Integer.MAX_VALUE));
				return;
			}
			THROTTLE.success(who);
			String body = read.get().body();
			if (body.length() > MAX_BODY) {
				reply(player, noteId, "UNAVAILABLE", 0);
				return;
			}
			NoteSummary note = read.get().note();
			MemoryPalace.LOGGER.info("{} 用阅读密码打开了笔记 {}", player.getName().getString(), note.relativePath());
			reply(player, noteId, "OK", 0);
			// 只读：editable = false（保存时服务端也会按权限拒绝）
			for (OpenNotePayload part : OpenNotePayload.split(noteId, note.title(), body, false, false)) {
				ServerPlayNetworking.send(player, part);
			}
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.error("核对阅读密码失败", e);
			reply(player, noteId, "UNAVAILABLE", 0);
		}
	}

	private static byte[] decode(String verifier) {
		try {
			return Base64.getDecoder().decode(verifier);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void reply(ServerPlayer player, UUID noteId, String result, int waitSeconds) {
		ServerPlayNetworking.send(player, new PasswordResult(noteId, result, waitSeconds));
	}
}
