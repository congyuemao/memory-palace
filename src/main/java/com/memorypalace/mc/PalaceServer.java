package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 管理"当前世界"的笔记库：世界开启时打开 saves/&lt;世界&gt;/memory-palace/，关闭时释放。
 */
public final class PalaceServer {
	public static final String VAULT_DIR = "memory-palace";

	private static volatile Vault vault;

	private PalaceServer() {}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path root = server.getWorldPath(LevelResource.ROOT).resolve(VAULT_DIR);
			try {
				Vault v = new Vault(root);
				writeWelcomeNoteIfEmpty(root);
				v.scan();
				vault = v;
				MemoryPalace.LOGGER.info("笔记库已打开：{}（{} 篇笔记）", v.root(), v.list().size());
			} catch (IOException e) {
				MemoryPalace.LOGGER.error("无法打开笔记库 {}", root, e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> vault = null);
	}

	/** 当前世界的笔记库；世界没开或打开失败时为 null。 */
	public static Vault vault() {
		return vault;
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

				这个文件夹就是本世界的笔记库：
				把 .md 文件放进来（可以建子文件夹），然后在游戏里输入：

				/palace give 欢迎

				就会得到这篇笔记的笔记书。把它插进雕纹书架，
				空手右键那一格就能打开；潜行 + 右键可以把书取出来。

				/palace list 可以列出所有笔记，/palace rescan 重新扫描文件夹。
				""", StandardCharsets.UTF_8);
	}
}
