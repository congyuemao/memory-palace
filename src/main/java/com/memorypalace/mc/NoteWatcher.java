package com.memorypalace.mc;

import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热重载：记住每个玩家最近打开的笔记，每秒看一眼文件修改时间，变了就把新内容推过去。
 * 只盯"正在看的那一篇"，所以开销只是每秒几次文件属性查询。
 */
final class NoteWatcher {
	private static final int CHECK_EVERY_TICKS = 20;

	private record Watched(UUID noteId, long modified) {}

	// 玩家 UUID → 正在看的笔记
	private static final Map<UUID, Watched> WATCHED = new ConcurrentHashMap<>();
	private static int tickCounter;

	private NoteWatcher() {}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tickCounter % CHECK_EVERY_TICKS != 0 || WATCHED.isEmpty()) {
				return;
			}
			for (var entry : WATCHED.entrySet()) {
				ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
				if (player == null) {
					WATCHED.remove(entry.getKey());
					continue;
				}
				Watched w = entry.getValue();
				long now = modifiedTime(w.noteId());
				if (now != w.modified()) {
					// 不管推送成没成功都记下新时间，免得文件坏了每秒重试刷日志
					PalaceInteractions.sendNote(player, w.noteId(), true);
					WATCHED.put(entry.getKey(), new Watched(w.noteId(), modifiedTime(w.noteId())));
				}
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCHED.remove(handler.getPlayer().getUUID()));
	}

	static void watch(ServerPlayer player, UUID noteId) {
		WATCHED.put(player.getUUID(), new Watched(noteId, modifiedTime(noteId)));
	}

	/** 文件修改时间；文件不见了（被删或改名）返回 -1。 */
	private static long modifiedTime(UUID noteId) {
		Vault vault = PalaceServer.vault();
		Optional<Path> path = vault == null ? Optional.empty() : vault.pathOf(noteId);
		if (path.isEmpty() || !Files.exists(path.get())) {
			return -1;
		}
		try {
			return Files.getLastModifiedTime(path.get()).toMillis();
		} catch (IOException e) {
			return -1;
		}
	}
}
