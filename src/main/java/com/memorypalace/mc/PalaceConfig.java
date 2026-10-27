package com.memorypalace.mc;

import com.electronwill.nightconfig.core.ConfigSpec;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteAccess;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 服务端配置 config/memorypalace-server.toml。局域网房主和专用服务器读的都是自己游戏目录下的这一份。
 * 读写交给 night-config（TOML、注释、默认值、校验）；文件不存在或有写错的项，自动补上默认值和注释再存回去。
 * 改完文件用 /palace reload 生效。
 */
public final class PalaceConfig {
	public static final String FILE_NAME = "memorypalace-server.toml";

	/**
	 * @param multiplayer        多人总开关。关着时只有本机房主能用笔记功能（单人游戏不受影响）
	 * @param defaultVisibility  世界没用 /palace visibility 设置过时，没写 palace-read 的笔记谁能读
	 * @param maxNotesPerPlayer  每个玩家最多建多少篇笔记（管理员不限）；0 = 不限
	 * @param maxNoteChars       普通玩家保存的一篇笔记最多多少字（管理员不限）；0 = 不限
	 */
	public record Settings(boolean multiplayer, NoteAccess.Visibility defaultVisibility,
			int maxNotesPerPlayer, int maxNoteChars) {
		public static final Settings DEFAULTS = new Settings(false, NoteAccess.Visibility.PRIVATE, 200, 200_000);
	}

	private static final String MULTIPLAYER = "multiplayer";
	private static final String DEFAULT_VISIBILITY = "default_visibility";
	private static final String MAX_NOTES = "max_notes_per_player";
	private static final String MAX_CHARS = "max_note_chars";

	private static volatile Settings current = Settings.DEFAULTS;

	private PalaceConfig() {}

	public static Settings get() {
		return current;
	}

	/** 读配置文件；读不了就保留现在的设置（第一次读失败 = 默认值，多人关闭）。 */
	public static synchronized void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		ConfigSpec spec = new ConfigSpec();
		Settings d = Settings.DEFAULTS;
		spec.define(MULTIPLAYER, d.multiplayer());
		// 不能用 List.of：night-config 补缺省值时会问 contains(null)，List.of 遇到 null 直接抛异常
		spec.defineInList(DEFAULT_VISIBILITY, d.defaultVisibility().id(), Arrays.asList("everyone", "private"));
		spec.defineInRange(MAX_NOTES, d.maxNotesPerPlayer(), 0, 1_000_000);
		spec.defineInRange(MAX_CHARS, d.maxNoteChars(), 0, 4_000_000);
		try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder().sync().build()) {
			boolean existed = Files.exists(file);
			config.load();
			// 缺的项先按固定顺序补上默认值（spec.correct 补的顺序是乱的），写错的值再交给 correct 改回默认
			int corrected = 0;
			for (var entry : List.of(Map.entry(MULTIPLAYER, (Object) d.multiplayer()),
					Map.entry(DEFAULT_VISIBILITY, d.defaultVisibility().id()),
					Map.entry(MAX_NOTES, d.maxNotesPerPlayer()), Map.entry(MAX_CHARS, d.maxNoteChars()))) {
				if (!config.contains(entry.getKey())) {
					config.set(entry.getKey(), entry.getValue());
					corrected++;
				}
			}
			corrected += spec.correct(config);
			if (!existed || corrected > 0) {
				addComments(config);
				config.save();
			}
			current = new Settings(config.<Boolean>get(MULTIPLAYER),
					NoteAccess.Visibility.parse(config.get(DEFAULT_VISIBILITY)),
					config.getInt(MAX_NOTES), config.getInt(MAX_CHARS));
			MemoryPalace.LOGGER.info("已读取 {}：多人 {}", file, current.multiplayer() ? "开启" : "关闭");
		} catch (RuntimeException e) {
			MemoryPalace.LOGGER.error("读取配置 {} 失败，沿用当前设置（多人：{}）", file, current.multiplayer() ? "开启" : "关闭", e);
		}
	}

	private static void addComments(CommentedFileConfig config) {
		comment(config, MULTIPLAYER,
				"多人总开关（局域网、服务器）。默认 false。",
				"false：只有本机房主能用笔记功能，其他玩家什么都看不到（单人游戏不受影响；专用服务器上没有房主，等于全部停用）。",
				"true：其他玩家也能用，按每篇笔记的权限来：能读谁的笔记、能不能新建、能不能分享，见 README。",
				"注意：没加密的笔记，服务器管理员（局域网里就是房主）在硬盘上都能看到。");
		comment(config, DEFAULT_VISIBILITY,
				"没写 palace-read 的笔记默认谁能读：\"private\"（只有作者和被分享的人，默认）或 \"everyone\"（所有人）。",
				"某个世界用 /palace visibility 设置过的，以世界设置为准。");
		comment(config, MAX_NOTES, "每个玩家最多建多少篇笔记，0 = 不限。管理员不受限制。");
		comment(config, MAX_CHARS, "普通玩家保存的一篇笔记最多多少字，0 = 不限。管理员不受限制。");
	}

	// TOML 注释每行写成 "# 文字"
	private static void comment(CommentedFileConfig config, String key, String... lines) {
		config.setComment(key, " " + String.join("\n ", lines));
	}
}
