package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteLinks;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 笔记之间的链接，都只在这个人读得了的笔记里找：读不了的和不存在的一样（不透露"有这么一篇"）。
 * <ul>
 * <li>OpenLink：书页上点了一个链接（[[笔记名]] 或 [文字](路径.md)），找到对应的笔记打开</li>
 * <li>Suggest / Suggestions：编辑器里输入 [[ 时的补全候选</li>
 * <li>InfoRequest / Info：打开一篇笔记后要它的断链（只算明文笔记：加密笔记的正文服务端读不出来，
 *   客户端也不把解密后的链接发过来）和反向链接，书页上把断链换个颜色、末尾列出反向链接</li>
 * <li>CreateFromLink：点了断链、确认新建：按链接建一篇新笔记并打开编辑器</li>
 * </ul>
 */
public final class LinkPayloads {
	public static final int MAX_LINK = 512;
	public static final int MAX_SUGGESTIONS = 8;
	public static final int MAX_BROKEN = 128;
	public static final int MAX_BACKLINKS = 30;

	private LinkPayloads() {}

	/** 客户端 → 服务端：在 source 这篇笔记里点了 link（MdSpan.link 的原样）。 */
	public record OpenLink(UUID source, String link) implements CustomPacketPayload {
		public static final Type<OpenLink> TYPE = new Type<>(MemoryPalace.id("open_link"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenLink> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, OpenLink::source,
				ByteBufCodecs.stringUtf8(MAX_LINK), OpenLink::link,
				OpenLink::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：补全 [[query 的候选。requestId 用来丢掉过期的结果。 */
	public record Suggest(int requestId, String query) implements CustomPacketPayload {
		public static final Type<Suggest> TYPE = new Type<>(MemoryPalace.id("link_suggest"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Suggest> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Suggest::requestId, ByteBufCodecs.stringUtf8(200), Suggest::query, Suggest::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 一个补全候选：insert 填进 [[ ]] 里，path 显示给人看。 */
	public record Candidate(String insert, String path) {
		static final StreamCodec<RegistryFriendlyByteBuf, Candidate> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(MAX_LINK), Candidate::insert, ByteBufCodecs.stringUtf8(MAX_LINK), Candidate::path, Candidate::new);
	}

	/** 服务端 → 客户端：补全候选。 */
	public record Suggestions(int requestId, List<Candidate> candidates) implements CustomPacketPayload {
		public static final Type<Suggestions> TYPE = new Type<>(MemoryPalace.id("link_suggestions"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Suggestions> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Suggestions::requestId,
				Candidate.CODEC.apply(ByteBufCodecs.list(MAX_SUGGESTIONS)), Suggestions::candidates,
				Suggestions::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：要这篇笔记的断链和反向链接。 */
	public record InfoRequest(UUID noteId) implements CustomPacketPayload {
		public static final Type<InfoRequest> TYPE = new Type<>(MemoryPalace.id("link_info_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, InfoRequest> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, InfoRequest::noteId, InfoRequest::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 一条反向链接：链接到这篇的笔记（path 是相对路径，title 是标题）。 */
	public record Backlink(String path, String title) {
		static final StreamCodec<RegistryFriendlyByteBuf, Backlink> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(MAX_LINK), Backlink::path, ByteBufCodecs.stringUtf8(256), Backlink::title, Backlink::new);
	}

	/**
	 * 服务端 → 客户端：broken 是正文里对应不上笔记的链接（MdSpan.link 的原样）；backlinks 是链接到这篇的笔记，
	 * moreBacklinks 是没列出来的篇数。
	 */
	public record Info(UUID noteId, List<String> broken, List<Backlink> backlinks, int moreBacklinks) implements CustomPacketPayload {
		public static final Type<Info> TYPE = new Type<>(MemoryPalace.id("link_info"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Info> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Info::noteId,
				ByteBufCodecs.stringUtf8(MAX_LINK).apply(ByteBufCodecs.list(MAX_BROKEN)), Info::broken,
				Backlink.CODEC.apply(ByteBufCodecs.list(MAX_BACKLINKS)), Info::backlinks,
				ByteBufCodecs.VAR_INT, Info::moreBacklinks,
				Info::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务端：在 source 这篇里点了指向不存在笔记的 link，确认新建。 */
	public record CreateFromLink(UUID source, String link) implements CustomPacketPayload {
		public static final Type<CreateFromLink> TYPE = new Type<>(MemoryPalace.id("create_from_link"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CreateFromLink> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, CreateFromLink::source,
				ByteBufCodecs.stringUtf8(MAX_LINK), CreateFromLink::link,
				CreateFromLink::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(OpenLink.TYPE, OpenLink.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Suggest.TYPE, Suggest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(InfoRequest.TYPE, InfoRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CreateFromLink.TYPE, CreateFromLink.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Suggestions.TYPE, Suggestions.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Info.TYPE, Info.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenLink.TYPE, (p, ctx) -> open(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Suggest.TYPE, (p, ctx) -> suggest(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(InfoRequest.TYPE, (p, ctx) -> info(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(CreateFromLink.TYPE, (p, ctx) -> create(ctx.player(), p));
	}

	/** 这个人读得了的笔记（在服务端线程上按此刻的权限算）。 */
	private static List<NoteSummary> readable(ServerPlayer player, Vault vault) {
		return vault.list().stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
	}

	// 补全：打字时客户端已经停手 150 毫秒才发，这里只挡住一直刷包
	private static final RateLimiter SUGGEST_RATE = new RateLimiter(8, 16);

	private static void suggest(ServerPlayer player, Suggest request) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player) || !SUGGEST_RATE.tryTake(player.getUUID())) {
			return;
		}
		List<Candidate> candidates = NoteLinks.suggest(readable(player, vault), request.query(), MAX_SUGGESTIONS).stream()
				.filter(s -> s.insert().length() <= MAX_LINK && s.path().length() <= MAX_LINK)
				.map(s -> new Candidate(s.insert(), s.path()))
				.toList();
		ServerPlayNetworking.send(player, new Suggestions(request.requestId(), candidates));
	}

	// 反向链接要读遍笔记库：放在后台线程，每人同一时间只跑一个；打开笔记很频繁，再限一下速
	private static final RateLimiter INFO_RATE = new RateLimiter(1, 5);
	private static final Set<UUID> INFO_RUNNING = ConcurrentHashMap.newKeySet();
	// 全服同时最多 2 个反向链接扫描（/palace backlinks 也用这个）：多个账号一起刷，磁盘和 Vault 锁的压力也有上限
	static final java.util.concurrent.Semaphore BACKLINK_SLOTS = new java.util.concurrent.Semaphore(2);

	/** 在 BACKLINK_SLOTS 里跑一次反向链接扫描（会等空位）；被打断时为空列表。 */
	static List<NoteSummary> backlinks(Vault vault, NoteSummary target, Set<UUID> visible) {
		try {
			BACKLINK_SLOTS.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		}
		try {
			return NoteLinks.backlinks(vault, target, n -> visible.contains(n.id()));
		} finally {
			BACKLINK_SLOTS.release();
		}
	}

	/** 后台算出来的反向链接，发回之前在服务端线程上按此刻的权限再筛一遍（算的时候权限可能变了）。 */
	static List<NoteSummary> stillReadable(ServerPlayer player, Vault vault, List<NoteSummary> found) {
		return found.stream()
				.map(n -> vault.find(n.id()).filter(now -> PalaceAccess.canRead(player, now)).orElse(null))
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	private static void info(ServerPlayer player, InfoRequest request) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player)) {
			return;
		}
		Optional<NoteSummary> note = vault.find(request.noteId()).filter(n -> PalaceAccess.canRead(player, n));
		if (note.isEmpty() || !INFO_RATE.tryTake(player.getUUID()) || !INFO_RUNNING.add(player.getUUID())) {
			return;
		}
		NoteSummary target = note.get();
		List<NoteSummary> readable = readable(player, vault);
		Set<UUID> visible = new HashSet<>();
		readable.forEach(n -> visible.add(n.id()));
		var server = player.level().getServer();
		Thread.ofVirtual().name("memorypalace-link-info").start(() -> {
			List<String> broken = new ArrayList<>();
			List<NoteSummary> backlinks;
			try {
				if (!target.encrypted()) {
					String body = vault.readBody(target.id()).orElse("");
					NoteLinks.Resolver resolver = new NoteLinks.Resolver(readable);
					for (String link : new LinkedHashSet<>(com.memorypalace.core.markdown.MarkdownParser.links(body))) {
						if (broken.size() < MAX_BROKEN && link.length() <= MAX_LINK && !NoteLinks.isExternal(link)
								&& resolver.resolve(target.relativePath(), link).isEmpty()) {
							broken.add(link);
						}
					}
				}
				backlinks = backlinks(vault, target, visible);
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.warn("整理笔记 {} 的链接失败", target.id(), e);
				return;
			} finally {
				INFO_RUNNING.remove(player.getUUID());
			}
			server.execute(() -> {
				if (player.hasDisconnected() || vault.find(target.id()).filter(n -> PalaceAccess.canRead(player, n)).isEmpty()) {
					return;
				}
				List<NoteSummary> readableNow = stillReadable(player, vault, backlinks);
				List<Backlink> shown = readableNow.stream().limit(MAX_BACKLINKS)
						.map(n -> new Backlink(OpenNotePayload.clip(n.relativePath(), MAX_LINK), OpenNotePayload.clip(n.title(), 256)))
						.toList();
				ServerPlayNetworking.send(player, new Info(target.id(), broken, shown, readableNow.size() - shown.size()));
			});
		});
	}

	/**
	 * 按断链新建：链接其实对应得上（别人刚建了）就直接打开那篇；否则按 NoteLinks.newNotePath 定位置。
	 * 普通玩家只能建在自己的个人区：链接指到个人区外面的，建在个人区根目录。权限、篇数上限等交给 NoteCommands.createNote。
	 */
	private static void create(ServerPlayer player, CreateFromLink request) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player)) {
			return;
		}
		Optional<NoteSummary> source = vault.find(request.source()).filter(n -> PalaceAccess.canRead(player, n));
		if (source.isEmpty()) {
			return;
		}
		Optional<NoteSummary> existing = NoteLinks.resolve(readable(player, vault), source.get().relativePath(), request.link());
		if (existing.isPresent()) {
			PalaceInteractions.openNote(player, existing.get().id());
			return;
		}
		Optional<String> path = NoteLinks.newNotePath(source.get().relativePath(), request.link());
		if (path.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.link_not_found", shown(request.link())));
			return;
		}
		String input = path.get();
		if (!PalaceAccess.admin(player)) {
			String personal = CommandSupport.personalFolder(player) + "/";
			input = input.startsWith(personal) ? input.substring(personal.length()) : input.substring(input.lastIndexOf('/') + 1);
		}
		Optional<NoteCommands.Created> created = NoteCommands.createNote(player, input, "", false, player::sendSystemMessage);
		if (created.isPresent()) {
			player.sendSystemMessage(Component.translatable("command.memorypalace.created", created.get().note().relativePath()));
			NoteCommands.openCreated(player, created.get(), false);
		}
	}

	// 点链接：每次都要把全库过一遍权限、建索引，找不到时又不经过 openNote 的限速，这里单独限一下
	private static final RateLimiter OPEN_RATE = new RateLimiter(3, 8);

	private static void open(ServerPlayer player, OpenLink request) {
		Vault vault = PalaceServer.vault();
		if (vault == null || !PalaceAccess.enabled(player) || !OPEN_RATE.tryTake(player.getUUID())) {
			return;
		}
		// 链接所在的那篇也要读得了：不然随便填个 ID 就能拿它当跳板试探别的笔记
		Optional<NoteSummary> source = vault.find(request.source()).filter(n -> PalaceAccess.canRead(player, n));
		if (source.isEmpty()) {
			return;
		}
		List<NoteSummary> readable = vault.list().stream().filter(n -> PalaceAccess.canRead(player, n)).toList();
		Optional<NoteSummary> target = NoteLinks.resolve(readable, source.get().relativePath(), request.link());
		if (target.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.memorypalace.link_not_found", shown(request.link())));
			return;
		}
		PalaceInteractions.openNote(player, target.get().id());
	}

	/** 给玩家看的链接目标：去掉 "wiki:"。 */
	static String shown(String link) {
		String text = link.startsWith(NoteLinks.WIKI) ? link.substring(NoteLinks.WIKI.length()) : link;
		return text.length() > 64 ? text.substring(0, 63) + "…" : text;
	}
}
