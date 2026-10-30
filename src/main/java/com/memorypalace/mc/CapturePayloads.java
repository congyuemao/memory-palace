package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.vault.CaptureService;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** 快速收集：随手记进今天的收件箱。 */
public final class CapturePayloads {
	private static final int MAX_CAPTURE = 1000;

	private CapturePayloads() {}

	/**
	 * 客户端 → 服务端：记进今天的收件箱。localTime 是玩家电脑上的本地时间（ISO 格式、带时区偏移），
	 * "今天"按玩家所在地算，而不是服务器所在地。
	 * requestId 是客户端给这条随手记起的编号，服务端用 CaptureResult 回同一个编号；retry = 同一条的重发（见 CaptureService）。
	 */
	public record Capture(UUID requestId, boolean retry, String text, String localTime) implements CustomPacketPayload {
		public static final Type<Capture> TYPE = new Type<>(MemoryPalace.id("capture"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Capture> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Capture::requestId,
				ByteBufCodecs.BOOL, Capture::retry,
				ByteBufCodecs.stringUtf8(MAX_CAPTURE), Capture::text,
				ByteBufCodecs.stringUtf8(64), Capture::localTime,
				Capture::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 客户端 → 服务端：记进今天的收件箱，但内容已经在客户端用收件箱公钥加密好了（InboxCipher.seal，一行 "MPALBOX1:…"）。
	 * 服务端只看得到"哪天记了一条、大概多长"，看不到内容。localTime、requestId、retry 同 Capture。
	 */
	public record CaptureSealed(UUID requestId, boolean retry, String line, String localTime) implements CustomPacketPayload {
		public static final Type<CaptureSealed> TYPE = new Type<>(MemoryPalace.id("capture_sealed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CaptureSealed> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, CaptureSealed::requestId,
				ByteBufCodecs.BOOL, CaptureSealed::retry,
				ByteBufCodecs.stringUtf8(InboxCipher.MAX_SEALED_CHARS), CaptureSealed::line,
				ByteBufCodecs.stringUtf8(64), CaptureSealed::localTime,
				CaptureSealed::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务端 → 客户端：requestId 这条随手记的保存结果。status 是 CaptureService.Status 的名字；
	 * OK 时 detail 是收件箱路径，QUOTA 时是篇数上限。每条随手记都会收到一个（没权限、限速、写入失败都算）。
	 */
	public record CaptureResult(UUID requestId, String status, String detail) implements CustomPacketPayload {
		public static final Type<CaptureResult> TYPE = new Type<>(MemoryPalace.id("capture_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CaptureResult> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, CaptureResult::requestId,
				ByteBufCodecs.stringUtf8(32), CaptureResult::status,
				ByteBufCodecs.stringUtf8(512), CaptureResult::detail,
				CaptureResult::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Capture.TYPE, Capture.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CaptureSealed.TYPE, CaptureSealed.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CaptureResult.TYPE, CaptureResult.CODEC);

		// 每人一个收件箱：房主的在根目录 收件箱/，其他玩家的在自己的个人区 players/<UUID>/收件箱/
		ServerPlayNetworking.registerGlobalReceiver(Capture.TYPE,
				(p, ctx) -> capture(ctx.player(), p.requestId(), p.retry(), p.localTime(),
						(vault, owner, time, retry) -> Inbox.append(vault, owner, time, p.text(), retry)));
		ServerPlayNetworking.registerGlobalReceiver(CaptureSealed.TYPE,
				(p, ctx) -> capture(ctx.player(), p.requestId(), p.retry(), p.localTime(),
						(vault, owner, time, retry) -> Inbox.appendSealed(vault, owner, time, p.line(), retry)));
	}

	private static final CaptureService CAPTURES = new CaptureService();

	// 每人一个收件箱：房主的在根目录 收件箱/，其他玩家的在自己的个人区 players/<UUID>/收件箱/。
	// 不管成功失败都回一个 CaptureResult，客户端据此决定这条随手记是保存了，还是要留着让玩家找回重试
	private static void capture(ServerPlayer player, UUID requestId, boolean retry, String localTime, CaptureService.Write write) {
		if (!PalaceAccess.allowed(player, PalaceAccess.CREATE)) {
			reply(player, requestId, PalaceAccess.enabled(player) ? "ADMIN_ONLY" : "MULTIPLAYER_OFF", "");
			return;
		}
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			reply(player, requestId, "NO_VAULT", "");
			return;
		}
		UUID owner = PalaceAccess.isHost(player) ? null : player.getUUID();
		int maxNotes = owner == null || PalaceAccess.admin(player) ? 0 : PalaceConfig.get().maxNotesPerPlayer();
		CaptureService.Result result = CAPTURES.capture(vault, player.getUUID(), owner, requestId, retry,
				playerTime(localTime), maxNotes, System.nanoTime() / 1_000_000, write);
		switch (result.status()) {
			case NOT_YOURS -> MemoryPalace.LOGGER.warn("{} 的收件箱文件作者不是本人，拒绝写入", player.getName().getString());
			case FAILED -> MemoryPalace.LOGGER.error("写入收件箱失败", result.error());
			default -> {
			}
		}
		reply(player, requestId, result.status().name(), result.detail());
	}

	private static void reply(ServerPlayer player, UUID requestId, String status, String detail) {
		ServerPlayNetworking.send(player, new CaptureResult(requestId, status, detail));
	}

	/**
	 * 玩家那边的本地时间。时刻和服务器差得太多（超过 1 小时，说明玩家电脑时钟不准或者数据是乱填的）、
	 * 或者格式不对，就退回服务器自己的时间。时区偏移照用玩家的。
	 */
	private static OffsetDateTime playerTime(String localTime) {
		OffsetDateTime server = OffsetDateTime.now();
		try {
			OffsetDateTime client = OffsetDateTime.parse(localTime);
			if (Math.abs(Duration.between(server.toInstant(), client.toInstant()).toMinutes()) <= 60) {
				return client;
			}
			return server.toInstant().atOffset(client.getOffset());
		} catch (DateTimeParseException e) {
			return server;
		}
	}
}
