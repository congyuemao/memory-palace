package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.search.NoteSearch;
import com.memorypalace.core.vault.CaptureService;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M7a 的三个小工具：快速收集、准星对准书时显示书名、找笔记。
 */
public final class ToolPayloads {
	private static final int MAX_CAPTURE = 1000;
	private static final int MAX_RESULTS = 20;

	private ToolPayloads() {}

	// ---- 快速收集 ----

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

	// ---- 准星对准书 ----

	/** 客户端 → 服务端：这个书架的这一格（讲台为 -1）是什么书？ */
	public record LookQuery(BlockPos pos, int slot) implements CustomPacketPayload {
		public static final Type<LookQuery> TYPE = new Type<>(MemoryPalace.id("look_query"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookQuery> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookQuery::pos, ByteBufCodecs.VAR_INT, LookQuery::slot, LookQuery::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 服务端 → 客户端：那一格的书名（"文件夹 / 标题"；不是笔记书时为 ""）。 */
	public record LookInfo(BlockPos pos, int slot, String text) implements CustomPacketPayload {
		public static final Type<LookInfo> TYPE = new Type<>(MemoryPalace.id("look_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LookInfo> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, LookInfo::pos, ByteBufCodecs.VAR_INT, LookInfo::slot,
				ByteBufCodecs.stringUtf8(512), LookInfo::text, LookInfo::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ---- 找笔记 ----

	/** 客户端 → 服务端：搜索。requestId 用来丢掉过期的结果（打字很快时）。 */
	public record Search(int requestId, String query) implements CustomPacketPayload {
		public static final Type<Search> TYPE = new Type<>(MemoryPalace.id("search"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Search> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Search::requestId, ByteBufCodecs.stringUtf8(200), Search::query, Search::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 一条搜索结果。dimension 为 "" 表示这篇笔记还没在任何书架/讲台上看过（没有位置）。
	 * teleport 是传送到书架正前方的命令（没有位置时为 ""）。
	 */
	public record Entry(UUID id, String title, String path, String snippet, boolean encrypted,
			String dimension, BlockPos pos, int slot, String teleport) {
		static final StreamCodec<FriendlyByteBuf, Entry> CODEC = StreamCodec.of(
				(buf, e) -> {
					buf.writeUUID(e.id);
					buf.writeUtf(e.title, 256);
					buf.writeUtf(e.path, 512);
					buf.writeUtf(e.snippet, 256);
					buf.writeBoolean(e.encrypted);
					buf.writeUtf(e.dimension, 128);
					buf.writeBlockPos(e.pos);
					buf.writeVarInt(e.slot);
					buf.writeUtf(e.teleport, 256);
				},
				buf -> new Entry(buf.readUUID(), buf.readUtf(256), buf.readUtf(512), buf.readUtf(256), buf.readBoolean(),
						buf.readUtf(128), buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256)));

		public boolean located() {
			return !dimension.isEmpty();
		}
	}

	/** 服务端 → 客户端：搜索结果。 */
	public record Results(int requestId, List<Entry> entries) implements CustomPacketPayload {
		public static final Type<Results> TYPE = new Type<>(MemoryPalace.id("search_results"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Results> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Results::requestId,
				Entry.CODEC.apply(ByteBufCodecs.list(MAX_RESULTS)).mapStream(b -> b), Results::entries,
				Results::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：直接打开这篇笔记（搜索结果里点"打开"）。 */
	public record OpenById(UUID id) implements CustomPacketPayload {
		public static final Type<OpenById> TYPE = new Type<>(MemoryPalace.id("open_by_id"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenById> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, OpenById::id, OpenById::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Capture.TYPE, Capture.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CaptureSealed.TYPE, CaptureSealed.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LookQuery.TYPE, LookQuery.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Search.TYPE, Search.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(OpenById.TYPE, OpenById.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CaptureResult.TYPE, CaptureResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LookInfo.TYPE, LookInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Results.TYPE, Results.CODEC);

		// 每人一个收件箱：房主的在根目录 收件箱/，其他玩家的在自己的个人区 players/<UUID>/收件箱/
		ServerPlayNetworking.registerGlobalReceiver(Capture.TYPE,
				(p, ctx) -> capture(ctx.player(), p.requestId(), p.retry(), p.localTime(),
						(vault, owner, time, retry) -> Inbox.append(vault, owner, time, p.text(), retry)));
		ServerPlayNetworking.registerGlobalReceiver(CaptureSealed.TYPE,
				(p, ctx) -> capture(ctx.player(), p.requestId(), p.retry(), p.localTime(),
						(vault, owner, time, retry) -> Inbox.appendSealed(vault, owner, time, p.line(), retry)));
		// 准星书名、搜索：谁都能用，但只看得到自己读得了的笔记（每一篇单独检查）
		ServerPlayNetworking.registerGlobalReceiver(LookQuery.TYPE, (p, ctx) -> look(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Search.TYPE, (p, ctx) -> search(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(OpenById.TYPE, (p, ctx) -> PalaceInteractions.openNote(ctx.player(), p.id()));
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

	private static void look(ServerPlayer player, LookQuery query) {
		String text = "";
		// 用原版的方块交互距离（和右键能够到的范围一致），而不是固定半径；多人关着时其他玩家什么都不显示
		if (PalaceAccess.enabled(player) && player.isWithinBlockInteractionRange(query.pos(), 1.0)) {
			ItemStack book = switch (player.level().getBlockEntity(query.pos())) {
				case ChiseledBookShelfBlockEntity shelf when query.slot() >= 0 && query.slot() < 6 -> shelf.getItem(query.slot());
				case LecternBlockEntity lectern when query.slot() == NoteLocations.LECTERN_SLOT -> lectern.getBook();
				case null, default -> ItemStack.EMPTY;
			};
			Vault vault = PalaceServer.vault();
			Optional<NoteSummary> note = vault == null ? Optional.empty() : NoteBooks.idOf(book).flatMap(vault::find);
			if (note.isPresent()) {
				text = label(note.get(), PalaceAccess.canRead(player, note.get()));
			}
		}
		ServerPlayNetworking.send(player, new LookInfo(query.pos(), query.slot(), text));
	}

	/**
	 * "文件夹 / 标题"。加密笔记不显示正文标题：作者看到"【加密】路径"；
	 * 读不了、标题也不是人人可见的笔记：和书名一样只有"【私密笔记】#7f3a"，不带路径（见 NoteBooks.titleFor）。
	 */
	private static String label(NoteSummary note, boolean canRead) {
		if (!canRead && (note.encrypted() || !PalaceAccess.titleIsPublic(note))) {
			return NoteBooks.titleFor(note); // 读不了：和书名一样，只有"【加密笔记】#7f3a"这种，不带路径
		}
		if (note.encrypted()) {
			String path = note.relativePath();
			return "【加密】" + path.substring(0, path.length() - 3);
		}
		return note.folder().isEmpty() ? note.title() : note.folder() + " / " + note.title();
	}

	// 每个玩家同时只跑一个搜索：正在搜的时候又来了新请求，只记住最新的一条，搜完再接着搜它（中间的直接丢掉）。
	// 这样狂发搜索请求也只会排队一条，不会堆出一大堆后台任务
	private static final Map<UUID, Search> LATEST = new ConcurrentHashMap<>();
	private static final Set<UUID> SEARCHING = ConcurrentHashMap.newKeySet();
	private static final long SEARCH_INTERVAL_MS = 300;
	private static final java.util.concurrent.Semaphore SEARCH_SLOTS = new java.util.concurrent.Semaphore(2);
	// 发起搜索时（在服务端线程上）记下"这个人是谁、是不是管理员"，后台线程按它过滤
	private static final Map<UUID, NoteAccess.Who> SEARCHER = new ConcurrentHashMap<>();

	private static void search(ServerPlayer player, Search request) {
		UUID id = player.getUUID();
		SEARCHER.put(id, PalaceAccess.who(player));
		LATEST.put(id, request);
		if (SEARCHING.add(id)) {
			Thread.ofVirtual().name("memorypalace-search").start(() -> searchLoop(player));
		}
	}

	private static void searchLoop(ServerPlayer player) {
		UUID id = player.getUUID();
		while (true) {
			Search next = LATEST.remove(id);
			if (next == null) {
				SEARCHING.remove(id);
				// 刚好在 remove 之后又来了请求：重新抢一下，抢到就继续，没抢到说明已经有别的线程在跑
				if (LATEST.containsKey(id) && SEARCHING.add(id)) {
					continue;
				}
				return;
			}
			runSearch(player, next);
			// 两次搜索之间至少隔一会儿：打字时的请求本来就会被合并，这里只挡住一直刷新请求、让服务端不停读全库
			try {
				Thread.sleep(SEARCH_INTERVAL_MS);
			} catch (InterruptedException e) {
				SEARCHING.remove(id);
				return;
			}
		}
	}

	private static void runSearch(ServerPlayer player, Search request) {
		Vault vault = PalaceServer.vault();
		NoteLocations locations = PalaceServer.locations();
		if (vault == null || player.hasDisconnected()) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		// 搜正文要读文件，在这个后台线程里做；位置和传送命令要读世界，回到服务端线程再算
		{
			NoteAccess.Who who = SEARCHER.get(player.getUUID());
			NoteAccess.Visibility visibility = PalaceServer.visibility();
			if (who == null) {
				return;
			}
			// 全服同时最多 2 个搜索在读盘：再多的玩家（或账号）一起刷搜索，磁盘压力也有上限
			List<NoteSearch.Hit> hits;
			try {
				SEARCH_SLOTS.acquire();
			} catch (InterruptedException e) {
				return;
			}
			try {
				hits = NoteSearch.search(vault, request.query(), MAX_RESULTS, note -> note.canRead(who, visibility));
			} finally {
				SEARCH_SLOTS.release();
			}
			server.execute(() -> {
				List<Entry> entries = new ArrayList<>();
				for (NoteSearch.Hit hit : hits) {
					if (entries.size() >= MAX_RESULTS) {
						break;
					}
					// 搜索在后台跑的时候权限可能变了（读正文时索引已按文件刷新）：发出去之前按此刻的权限再查一次
					NoteAccess.Who now = PalaceAccess.who(player);
					NoteSummary note = vault.find(hit.note().id())
							.filter(n -> n.canRead(now, PalaceServer.visibility())).orElse(null);
					if (note == null) {
						continue;
					}
					String title = OpenNotePayload.clip(note.encrypted() ? label(note, true) : note.title(), 256);
					String path = OpenNotePayload.clip(note.relativePath(), 512);
					String snippet = OpenNotePayload.clip(hit.snippet(), 256);
					// 同一篇笔记放在好几个书架上（书可以复制）：每个位置一行
					List<NoteLocations.Location> places = locations == null ? List.of() : validPlaces(server, locations, note);
					if (places.isEmpty()) {
						entries.add(new Entry(note.id(), title, path, snippet, note.encrypted(), "", BlockPos.ZERO, 0, ""));
						continue;
					}
					for (NoteLocations.Location loc : sortByDistance(places, player)) {
						if (entries.size() >= MAX_RESULTS) {
							break;
						}
						ServerLevel level = PalaceCommands.findLevel(server, loc.dimension());
						BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
						String tp = PalaceCommands.teleportCommand(loc, level != null && level.isLoaded(pos) ? level : null);
						entries.add(new Entry(note.id(), title, path, snippet, note.encrypted(), loc.dimension(), pos, loc.slot(), tp));
					}
				}
				if (!player.hasDisconnected()) {
					ServerPlayNetworking.send(player, new Results(request.requestId(), entries));
				}
			});
		}
	}

	/**
	 * 这篇笔记现在还在的位置。和 /palace where 一样顺手检查：书架拆了、那一格换成别的书了，就删掉这条记录；
	 * 区块没加载没法查的照常保留。
	 */
	private static List<NoteLocations.Location> validPlaces(MinecraftServer server, NoteLocations locations, NoteSummary note) {
		List<NoteLocations.Location> out = new ArrayList<>();
		for (NoteLocations.Location loc : locations.get(note.id())) {
			ServerLevel level = PalaceCommands.findLevel(server, loc.dimension());
			BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
			if (level == null || (level.isLoaded(pos) && !PalaceCommands.stillThere(level, pos, loc.slot(), note.id()))) {
				try {
					locations.remove(note.id(), loc);
				} catch (IOException e) {
					MemoryPalace.LOGGER.warn("删除过期位置记录失败", e);
				}
				continue;
			}
			out.add(loc);
		}
		return out;
	}

	/** 玩家所在维度的排前面，同维度里离玩家越近越靠前。 */
	private static List<NoteLocations.Location> sortByDistance(List<NoteLocations.Location> places, ServerPlayer player) {
		String dim = player.level().dimension().identifier().toString();
		Vec3 here = player.position();
		return places.stream().sorted(java.util.Comparator
				.comparing((NoteLocations.Location l) -> !l.dimension().equals(dim))
				.thenComparingDouble(l -> here.distanceToSqr(l.x() + 0.5, l.y() + 0.5, l.z() + 0.5)))
				.toList();
	}
}
