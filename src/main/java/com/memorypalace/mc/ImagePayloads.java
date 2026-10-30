package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.render.NoteImages;
import com.memorypalace.core.render.Raster;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.core.vault.NoteAccess;
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
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 笔记里的图片：客户端问"这篇笔记里的 src 这张图"，服务端读文件、缩小、编码成 PNG 发回去。
 * 和笔记正文一样走网络，以后多人服务器也能用。
 */
public final class ImagePayloads {
	// 一个包最多约 1MB，留点余量
	private static final int MAX_BYTES = 900 * 1024;
	private static final int MAX_DIM = 1024;

	private ImagePayloads() {}

	/** 客户端 → 服务端：请求图片。 */
	public record Request(UUID noteId, String src) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(MemoryPalace.id("image_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Request::noteId,
				ByteBufCodecs.stringUtf8(1024), Request::src,
				Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：图片数据（PNG）。png 为空数组表示找不到或读不了。 */
	public record Data(UUID noteId, String src, byte[] png) implements CustomPacketPayload {
		public static final Type<Data> TYPE = new Type<>(MemoryPalace.id("image_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Data::noteId,
				ByteBufCodecs.stringUtf8(1024), Data::src,
				ByteBufCodecs.byteArray(MAX_BYTES), Data::png,
				Data::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Data.TYPE, Data.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Vault vault = PalaceServer.vault();
			if (vault == null) {
				send(player, payload, new byte[0]);
				return;
			}
			// 每人同时最多 PER_PLAYER 个、全服最多 GLOBAL 个在处理；超了直接回"没有"（客户端以后重开笔记会再要）
			UUID playerId = player.getUUID();
			if (!RATE.tryTake(playerId)) {
				send(player, payload, new byte[0]);
				return;
			}
			AtomicInteger mine = IN_FLIGHT.computeIfAbsent(playerId, id -> new AtomicInteger());
			if (mine.incrementAndGet() > PER_PLAYER || !GLOBAL.tryAcquire()) {
				mine.decrementAndGet();
				send(player, payload, new byte[0]);
				return;
			}
			// 权限按发请求这一刻的身份算；读文件、找图、缩放、编码放到后台线程，做完回到服务端线程发送
			NoteAccess.Who who = PalaceAccess.who(player);
			NoteAccess.Visibility visibility = PalaceServer.visibility();
			Thread.ofVirtual().name("memorypalace-image").start(() -> {
				byte[] png = new byte[0];
				try {
					png = loadAllowed(vault, payload, who, visibility);
				} finally {
					GLOBAL.release();
					mine.decrementAndGet();
				}
				byte[] result = png;
				player.level().getServer().execute(() -> {
					// 处理期间权限可能被收回：发出去前按此刻的身份和卡片再查一次笔记本身
					boolean still = vault.find(payload.noteId()).filter(n -> PalaceAccess.canRead(player, n)).isPresent();
					send(player, payload, still ? result : new byte[0]);
				});
			});
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			IN_FLIGHT.remove(handler.getPlayer().getUUID());
			RATE.forget(handler.getPlayer().getUUID());
		});
	}

	private static final int PER_PLAYER = 4;
	// 每人每秒补 3 个请求额度、最多攒 30 个：一篇图很多的笔记打开时够用，一直刷就会被挡
	private static final RateLimiter RATE = new RateLimiter(3, 30);
	private static final Semaphore GLOBAL = new Semaphore(8);
	private static final Map<UUID, AtomicInteger> IN_FLIGHT = new ConcurrentHashMap<>();

	/**
	 * 能给这个人看的图才读：
	 * - 笔记本身要读得了（按文件此刻的权限）
	 * - src 必须真的写在这篇笔记的正文里（不能拿一篇公开笔记的 ID 去要任意文件名）
	 * - src 里不能有 ".."
	 * - 图片所在的文件夹不能是收件箱，也不能放着这个人读不了的笔记（私密、加密笔记旁边的图跟着它们一起保密）
	 */
	private static byte[] loadAllowed(Vault vault, Request request, NoteAccess.Who who, NoteAccess.Visibility visibility) {
		try {
			Optional<Vault.Read> read = vault.readNote(request.noteId());
			String src = request.src();
			if (read.isEmpty() || !read.get().note().canRead(who, visibility) || src.isBlank()
					|| !referencedAsImage(read.get().body(), src) || hasParentSegment(src)) {
				return new byte[0];
			}
			Path root = vault.root().toAbsolutePath().normalize();
			Optional<Path> file = NoteImages.resolve(root, read.get().note().relativePath(), src);
			if (file.isEmpty()) {
				return new byte[0];
			}
			Path parent = root.relativize(file.get().toAbsolutePath().normalize()).getParent();
			String folder = parent == null ? "" : parent.toString().replace('\\', '/');
			// 图片要和引用它的笔记在同一个区域：同一个玩家的个人区，或者都不在任何个人区里。
			// 否则玩家 A 在自己笔记里写 ![](players/<B>/图.png)，就能拿到 B 个人区里的图
			if (!personalArea(folder).equals(personalArea(read.get().note().folder()))) {
				return new byte[0];
			}
			if (!folderReadable(vault, folder, who, visibility)) {
				return new byte[0];
			}
			return loadPng(file.get());
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.warn("读取笔记图片失败：{}", request.src(), e);
			return new byte[0];
		}
	}

	/**
	 * 图片所在的文件夹，以及它往上的每一级（一直到根目录），都不能是收件箱、也不能放着这个人读不了的笔记：
	 * "私密/附件/图.png" 跟着 "私密/" 里的笔记一起保密；Obsidian 的中央附件文件夹 "assets/" 跟着根目录里的笔记一起保密。
	 * 宁可多挡：根目录里只要有一篇这个人读不了的笔记，他就看不到任何图。私密笔记请放进子文件夹。
	 * 例外：玩家个人区 players/&lt;UUID&gt;/ 里的图只查到个人区为止，不受个人区外面（根目录等）的笔记牵连，
	 * 否则"默认私有"的世界里，根目录有一篇别人读不了的笔记，所有玩家连自己的图都看不到。
	 */
	private static boolean folderReadable(Vault vault, String folder, NoteAccess.Who who, NoteAccess.Visibility visibility) {
		String f = folder;
		String top = personalArea(folder);
		while (true) {
			if (Inbox.isInboxFolder(f)) {
				return false;
			}
			if (vault.listFolder(f).stream().anyMatch(n -> !n.canRead(who, visibility))) {
				return false;
			}
			if (f.equals(top)) {
				return true;
			}
			int slash = f.lastIndexOf('/');
			f = slash < 0 ? "" : f.substring(0, slash);
		}
	}

	// 文件夹在哪个玩家的个人区里就返回 "players/<UUID>"，不在个人区里返回 ""（根目录）
	private static String personalArea(String folder) {
		String prefix = CommandSupport.PLAYERS_FOLDER + "/";
		if (!folder.startsWith(prefix)) {
			return "";
		}
		int slash = folder.indexOf('/', prefix.length());
		return slash < 0 ? folder : folder.substring(0, slash);
	}

	/** src 确实是这篇正文里的图片引用：Markdown 的 ](src 或 Obsidian 的 [[src（也接受 <src> 写法）。 */
	private static boolean referencedAsImage(String body, String src) {
		return body.contains("](" + src) || body.contains("](<" + src) || body.contains("[[" + src);
	}

	/** 有没有 ".." 这一级。和 NoteImages.resolve 一样先做 URL 解码（%2e%2e%2f 解出来就是 ../），再检查。 */
	private static boolean hasParentSegment(String src) {
		String s = src;
		try {
			s = java.net.URLDecoder.decode(src.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ignored) {
			// 不完整的百分号编码：NoteImages 也按原样找，这里按原样检查
		}
		s = s.replace('\\', '/').toLowerCase(java.util.Locale.ROOT).replace("%2e", ".");
		for (String part : s.split("/")) {
			if (part.equals("..")) {
				return true;
			}
		}
		return false;
	}

	private static void send(ServerPlayer player, Request request, byte[] png) {
		if (!player.hasDisconnected()) {
			ServerPlayNetworking.send(player, new Data(request.noteId(), request.src(), png));
		}
	}

	private static byte[] loadPng(Path file) {
		try {
			// 照片编码成 PNG 可能超过一个包的上限：超了就缩小一点再试
			for (int dim = MAX_DIM; dim >= 64; dim = dim * 3 / 4) {
				Optional<Raster> raster = NoteImages.load(file, dim);
				if (raster.isEmpty()) {
					return new byte[0];
				}
				byte[] png = NoteImages.toPng(raster.get());
				if (png.length <= MAX_BYTES) {
					return png;
				}
			}
			return new byte[0];
		} catch (RuntimeException e) {
			MemoryPalace.LOGGER.warn("读取笔记图片失败：{}", file, e);
			return new byte[0];
		}
	}
}
