package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteLocations;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.core.vault.VaultBackup;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
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
	private static final int KEEP_BACKUPS = 10;

	private static volatile Vault vault;
	private static volatile NoteLocations locations;
	private static volatile NoteAccess.Visibility visibility = NoteAccess.Visibility.EVERYONE;
	// 每 2 秒在后台看一眼哪些笔记文件在外部被改过，按文件刷新权限（见 Vault.refreshChanged）
	private static final long REFRESH_EVERY_MS = 2_000;
	private static volatile ScheduledExecutorService refresher;

	private PalaceServer() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path root = worldDir.resolve(VAULT_DIR);
			try {
				Vault v = new Vault(root);
				writeWelcomeNoteIfEmpty(root);
				v.scan();
				visibility = loadVisibility(root);
				vault = v;
				locations = new NoteLocations(root.resolve(".palace").resolve("locations.tsv"));
				MemoryPalace.LOGGER.info("笔记库已打开：{}（{} 篇笔记）", v.root(), v.list().size());
				startRefresher(v);
				backupInBackground(root, worldDir.getFileName().toString());
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
			visibility = NoteAccess.Visibility.EVERYONE;
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

	/** 世界设置"笔记可见性"：没写 palace-read 的笔记谁能读。存在 .palace/settings.properties。 */
	public static NoteAccess.Visibility visibility() {
		return visibility;
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
		visibility = value;
	}

	private static final String VISIBILITY_KEY = "visibility";

	private static Path settingsFile(Path root) {
		return root.resolve(".palace").resolve("settings.properties");
	}

	// 读不出来就用默认值"所有人可读"，并记一条日志
	private static NoteAccess.Visibility loadVisibility(Path root) {
		Path file = settingsFile(root);
		if (!Files.exists(file)) {
			return NoteAccess.Visibility.EVERYONE;
		}
		Properties props = new Properties();
		try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(in);
		} catch (IOException | IllegalArgumentException e) {
			MemoryPalace.LOGGER.warn("读取世界设置失败，笔记可见性按默认（所有人可读）", e);
			return NoteAccess.Visibility.EVERYONE;
		}
		return NoteAccess.Visibility.parse(props.getProperty(VISIBILITY_KEY));
	}

	/** 当前世界的笔记库；世界没开或打开失败时为 null。 */
	public static Vault vault() {
		return vault;
	}

	/** 当前世界的笔记位置记录；世界没开时为 null。 */
	public static NoteLocations locations() {
		return locations;
	}

	/**
	 * 每次进世界把笔记库备份到 .minecraft/memory-palace-backups/&lt;世界文件夹名&gt;/，
	 * 放在存档外面，删世界也不会丢。没改过就不重复备份。在后台线程做，不卡进世界。
	 */
	private static void backupInBackground(Path vaultRoot, String worldFolder) {
		Path backupDir = FabricLoader.getInstance().getGameDir().resolve(BACKUP_DIR).resolve(worldFolder);
		Thread.ofVirtual().name("memorypalace-backup").start(() -> {
			try {
				VaultBackup.backup(vaultRoot, backupDir, KEEP_BACKUPS, Clock.systemDefaultZone())
						.ifPresent(zip -> MemoryPalace.LOGGER.info("笔记库已备份：{}", zip));
			} catch (IOException | RuntimeException e) {
				MemoryPalace.LOGGER.error("笔记库备份失败", e);
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

				每次进入世界时，笔记库会自动备份至 .minecraft/memory-palace-backups/。
				""", StandardCharsets.UTF_8);
	}
}
