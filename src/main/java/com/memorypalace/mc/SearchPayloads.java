package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.search.NoteSearch;
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
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** 找笔记：搜索框的搜索、结果里的位置和传送命令、从结果直接打开。 */
public final class SearchPayloads {
	private static final int MAX_RESULTS = 20;

	private SearchPayloads() {}

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
		PayloadTypeRegistry.serverboundPlay().register(Search.TYPE, Search.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(OpenById.TYPE, OpenById.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Results.TYPE, Results.CODEC);
		// 搜索：谁都能用，但只看得到自己读得了的笔记（每一篇单独检查）
		ServerPlayNetworking.registerGlobalReceiver(Search.TYPE, (p, ctx) -> search(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(OpenById.TYPE, (p, ctx) -> PalaceInteractions.openNote(ctx.player(), p.id()));
	}

	// 每个玩家同时只跑一个搜索：正在搜的时候又来了新请求，只记住最新的一条，搜完再接着搜它（中间的直接丢掉）。
	// 这样狂发搜索请求也只会排队一条，不会堆出一大堆后台任务
	private static final Map<UUID, Search> LATEST = new ConcurrentHashMap<>();
	private static final Set<UUID> SEARCHING = ConcurrentHashMap.newKeySet();
	private static final long SEARCH_INTERVAL_MS = 300;
	private static final Semaphore SEARCH_SLOTS = new Semaphore(2);
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
					String title = OpenNotePayload.clip(note.encrypted() ? LookPayloads.label(note, true) : note.title(), 256);
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
						ServerLevel level = LocationCommands.findLevel(server, loc.dimension());
						BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
						String tp = LocationCommands.teleportCommand(loc, level != null && level.isLoaded(pos) ? level : null);
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
			ServerLevel level = LocationCommands.findLevel(server, loc.dimension());
			BlockPos pos = new BlockPos(loc.x(), loc.y(), loc.z());
			if (level == null || (level.isLoaded(pos) && !LocationCommands.stillThere(level, pos, loc.slot(), note.id()))) {
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
		return places.stream().sorted(Comparator
				.comparing((NoteLocations.Location l) -> !l.dimension().equals(dim))
				.thenComparingDouble(l -> here.distanceToSqr(l.x() + 0.5, l.y() + 0.5, l.z() + 0.5)))
				.toList();
	}
}
