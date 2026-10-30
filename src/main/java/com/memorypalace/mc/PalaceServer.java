package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.CopyJobs;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.NoteHistory;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.core.vault.VaultBackup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 管理"当前世界"的笔记库：世界开启时打开 saves/&lt;世界&gt;/memory-palace/，关闭时释放。
 */
public final class PalaceServer {
	public static final String VAULT_DIR = "memory-palace";
	private static final String BACKUP_DIR = "memory-palace-backups";
	// 增量备份：没改过的内容只存一份，多留几份也不怎么占地方
	private static final int KEEP_BACKUPS = 30;

	private static volatile Vault vault;
	private static volatile NoteLocations locations;
	private static volatile CopyJobs copyJobs;
	private static volatile MinecraftServer server;
	// 备份放在 .minecraft/memory-palace-backups/<世界文件夹名>/
	private static volatile Path backupDir;
	// 世界设置的笔记可见性；null = 这个世界没设置过，用配置文件里的默认值
	private static volatile NoteAccess.Visibility worldVisibility;
	// 每 2 秒在后台看一眼哪些笔记文件在外部被改过，按文件刷新权限（见 Vault.refreshChanged）
	private static final long REFRESH_EVERY_MS = 2_000;
	private static volatile ScheduledExecutorService refresher;

	private PalaceServer() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> PalaceConfig.load());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			PalaceServer.server = server;
			Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path root = worldDir.resolve(VAULT_DIR);
			try {
				Vault v = new Vault(root);
				v.setHistory(new NoteHistory(v.root(), Clock.systemDefaultZone())); // 游戏内保存时留历史版本；"每小时、每天留一份"按本机时区
				writeWelcomeNoteIfEmpty(root);
				v.scan();
				worldVisibility = loadVisibility(root);
				vault = v;
				locations = new NoteLocations(root.resolve(".palace").resolve("locations.tsv"));
				copyJobs = new CopyJobs(root.resolve(".palace").resolve("copy-jobs.tsv"));
				MemoryPalace.LOGGER.info("笔记库已打开：{}（{} 篇笔记）", v.root(), v.list().size());
				startRefresher(v);
				backupDir = FabricLoader.getInstance().getGameDir().resolve(BACKUP_DIR).resolve(worldDir.getFileName().toString());
				backupInBackground(root, backupDir, ok -> {});
			} catch (IOException e) {
				MemoryPalace.LOGGER.error("无法打开笔记库 {}", root, e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ScheduledExecutorService r = refresher;
			if (r != null) {
				r.shutdownNow();
				refresher = null;
			}
			vault = null;
			locations = null;
			copyJobs = null;
			worldVisibility = null;
			backupDir = null;
			PalaceServer.server = null;
		});
	}

	private static void startRefresher(Vault v) {
		ScheduledExecutorService r = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
				.name("memorypalace-refresh").daemon(true).factory());
		r.scheduleWithFixedDelay(() -> {
			try {
				v.refreshChanged();
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.warn("刷新笔记索引失败", e);
			}
		}, REFRESH_EVERY_MS, REFRESH_EVERY_MS, TimeUnit.MILLISECONDS);
		refresher = r;
	}

	/**
	 * "笔记可见性"：没写 palace-read 的笔记谁能读。世界设置过（/palace visibility，存在 .palace/settings.properties）
	 * 就用世界的，否则用配置文件里的 default_visibility。
	 */
	public static NoteAccess.Visibility visibility() {
		NoteAccess.Visibility world = worldVisibility;
		return world != null ? world : PalaceConfig.get().defaultVisibility();
	}

	public static void setVisibility(NoteAccess.Visibility value) throws IOException {
		Vault v = vault;
		if (v == null) {
			throw new IOException("no vault");
		}
		Path file = settingsFile(v.root());
		Properties props = new Properties();
		if (Files.exists(file)) {
			try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				props.load(in);
			}
		}
		props.setProperty(VISIBILITY_KEY, value.id());
		Files.createDirectories(file.getParent());
		Path tmp = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
		try {
			try (var out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				props.store(out, "Memory Palace world settings");
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
		worldVisibility = value;
	}

	private static final String VISIBILITY_KEY = "visibility";

	private static Path settingsFile(Path root) {
		return root.resolve(".palace").resolve("settings.properties");
	}

	// 没设置过、或者读不出来（记一条日志）：返回 null，用配置文件里的默认值
	private static NoteAccess.Visibility loadVisibility(Path root) {
		Path file = settingsFile(root);
		if (!Files.exists(file)) {
			return null;
		}
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(in);
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.warn("读取世界设置失败，笔记可见性按配置文件的默认值", e);
			return null;
		}
		String value = props.getProperty(VISIBILITY_KEY);
		return value == null ? null : NoteAccess.Visibility.parse(value);
	}

	/** 除了房主还可能有别人：开放到了局域网，或者是专用服务器（不知道时也当作有）。 */
	public static boolean othersCanJoin() {
		MinecraftServer s = server;
		return s == null || s.isDedicatedServer() || s.isPublished();
	}

	/** 玩家名：在线的直接取，不在线的查服务器的名字缓存（usercache.json）；都没有时 empty。 */
	public static Optional<String> playerName(java.util.UUID id) {
		MinecraftServer s = server;
		if (s == null) {
			return Optional.empty();
		}
		var online = s.getPlayerList().getPlayer(id);
		if (online != null) {
			return Optional.of(online.getGameProfile().name());
		}
		return s.services().nameToIdCache().get(id).map(net.minecraft.server.players.NameAndId::name);
	}

	/** 房主的名字（单人游戏、自己开的局域网）；专用服务器上没有房主，empty。 */
	public static Optional<String> hostName() {
		MinecraftServer s = server;
		if (s == null || s.isDedicatedServer() || s.getSingleplayerProfile() == null) {
			return Optional.empty();
		}
		return Optional.of(s.getSingleplayerProfile().name());
	}

	/** 当前世界的笔记库；世界没开或打开失败时为 null。 */
	public static Vault vault() {
		return vault;
	}

	/** 当前世界的笔记位置记录；世界没开时为 null。 */
	/** 抄进被锁文件夹的进行中记录（见 CopyJobs）；笔记库没打开时为 null。 */
	public static CopyJobs copyJobs() {
		return copyJobs;
	}

	public static NoteLocations locations() {
		return locations;
	}

	/** 当前世界的备份文件夹；世界没开时为 null。 */
	public static Path backupDir() {
		return backupDir;
	}

	/**
	 * 每次进世界把笔记库备份到 .minecraft/memory-palace-backups/&lt;世界文件夹名&gt;/，
	 * 放在存档外面，删世界也不会丢。没改过就不重复备份。在后台线程做，不卡进世界。
	 * done(true) = 备份成功（包括内容没变、不用备份）。
	 */
	private static void backupInBackground(Path vaultRoot, Path dir, java.util.function.Consumer<Boolean> done) {
		Thread.ofVirtual().name("memorypalace-backup").start(() -> done.accept(backupNow(vaultRoot, dir)));
	}

	private static boolean backupNow(Path vaultRoot, Path dir) {
		try {
			VaultBackup.backup(vaultRoot, dir, KEEP_BACKUPS, Clock.systemDefaultZone())
					.ifPresentOrElse(
							zip -> MemoryPalace.LOGGER.info("笔记库备份已保存：{}", zip),
							() -> MemoryPalace.LOGGER.debug("笔记库内容未变化或首次备份时为空，未生成新备份"));
			return true;
		} catch (VaultBackup.IncompleteBackupException e) {
			MemoryPalace.LOGGER.error("笔记库未能完整读取，本次备份已放弃，已有备份未清理", e);
		} catch (IOException | RuntimeException e) {
			MemoryPalace.LOGGER.error("笔记库备份失败", e);
		}
		return false;
	}

	/** 设置里的"立即备份"：在后台备份一次，done 在后台线程调用（true = 成功或内容没变）。 */
	public static void backupNow(java.util.function.Consumer<Boolean> done) {
		Vault v = vault;
		Path dir = backupDir;
		if (v == null || dir == null) {
			done.accept(false);
			return;
		}
		backupInBackground(v.root(), dir, done);
	}

	/**
	 * 把笔记库恢复成某一次备份（只在本机房主的设置里用）。在服务端线程上做，期间没有别的保存插进来：
	 * 先备份一次当前内容（失败就不恢复），再恢复，然后重新扫描笔记库。done 在服务端线程调用。
	 */
	public static void restoreBackup(String snapshotId, java.util.function.Consumer<VaultBackup.RestoredInPlace> done,
			java.util.function.Consumer<String> failed) {
		MinecraftServer s = server;
		Vault v = vault;
		Path dir = backupDir;
		if (s == null || v == null || dir == null) {
			failed.accept("no vault");
			return;
		}
		s.execute(() -> {
			if (!backupNow(v.root(), dir)) {
				failed.accept("backup failed");
				return;
			}
			try {
				String stamp = "恢复备份前-" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
						.format(java.time.Instant.now().atZone(java.time.ZoneOffset.UTC));
				VaultBackup.RestoredInPlace result = VaultBackup.restoreInto(dir, snapshotId, v.root(), stamp);
				v.scan();
				Path root = v.root();
				worldVisibility = loadVisibility(root);
				locations = new NoteLocations(root.resolve(".palace").resolve("locations.tsv"));
				copyJobs = new CopyJobs(root.resolve(".palace").resolve("copy-jobs.tsv"));
				MemoryPalace.LOGGER.info("笔记库已恢复到备份 {}：写回 {} 个文件，{} 个文件移到了 {}", snapshotId,
						result.written(), result.movedToTrash(), result.trash());
				done.accept(result);
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.error("恢复备份失败", e);
				failed.accept(e.getMessage());
			}
		});
	}

	// 新世界第一次打开时放一篇说明，免得笔记库空空的不知道怎么用
	private static void writeWelcomeNoteIfEmpty(Path root) throws IOException {
		try (var entries = Files.list(root)) {
			if (entries.findAny().isPresent()) {
				return;
			}
		}
		Files.writeString(root.resolve("欢迎.md"), """
				# 欢迎来到记忆宫殿

				本文件夹即为当前世界的笔记库。
				将 .md 文件放入本文件夹（可建立子文件夹）后，在游戏中输入：

				/palace give 欢迎

				即可领取本笔记对应的笔记书。将其放入雕纹书架后，右键对应格子即可阅读；
				潜行 + 右键可将书取出。笔记书也可放置于讲台上，右键阅读。

				- /palace list：列出所有笔记
				- /palace rescan：重新扫描笔记库
				- /palace where 笔记名：查看该笔记所在的书架与讲台
				- /palace new 路径：新建笔记并在游戏内编辑
				- /palace newsecret 路径：新建加密笔记
				- /palace newfolder 路径：新建文件夹
				- /palace rename "新名字" 笔记名：给笔记改名
				- /palace delete 笔记或文件夹：删除（移进 .palace/trash 回收站，可以找回）
				- /palace history 笔记名：查看、还原历史版本
				- /palace backlinks 笔记名：列出链接到这篇的笔记

				在笔记里写 [[另一篇笔记]]，阅读时单击即可跳转。

				每次进入世界时，笔记库会自动备份至 .minecraft/memory-palace-backups/。
				""", StandardCharsets.UTF_8);
	}
}
