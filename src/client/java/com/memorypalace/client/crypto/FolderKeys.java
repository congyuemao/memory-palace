package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.FolderPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 解锁过的文件夹钥匙（文件夹独立密码）：文件夹钥匙 ID → 钥匙。只在内存里，上锁、离开世界时清零。
 * 也负责向服务端要 / 写锁文件（.palace-lock，里面是用文件夹密码锁住的文件夹钥匙，没有密码）。只在渲染线程使用。
 */
public final class FolderKeys {
	private static final Map<String, MasterKey> KEYS = new HashMap<>();
	// 等锁文件回来的：笔记 ID → 回调（同一篇只发一次请求）
	private static final Map<UUID, List<Consumer<FolderPayloads.LockData>>> WAITING = new HashMap<>();
	private static int writeCounter;
	private static final Map<Integer, Consumer<Boolean>> WRITES = new HashMap<>();

	private FolderKeys() {}

	public static MasterKey get(String id) {
		MasterKey key = KEYS.get(id);
		if (key != null && !key.isDestroyed()) {
			ClientKeys.touch();
			return key;
		}
		return null;
	}

	public static void put(String id, MasterKey key) {
		MasterKey old = KEYS.put(id, key);
		if (old != null && old != key) {
			old.destroy();
		}
		ClientKeys.touch();
	}

	/** 有没有解锁着的文件夹（自动上锁要算上它们）。 */
	public static boolean any() {
		return KEYS.values().stream().anyMatch(k -> !k.isDestroyed());
	}

	/** 上锁、离开世界：全部清零，丢掉没回来的请求。 */
	public static void clear() {
		KEYS.values().forEach(MasterKey::destroy);
		KEYS.clear();
		WAITING.clear();
		WRITES.clear();
	}

	/**
	 * 确保这篇笔记所在文件夹的钥匙已经解锁（lockId 是笔记里 f 锁的 ID），再做 then。
	 * 还没解锁：向服务端要锁文件，弹出"解锁文件夹"输密码。服务端发来的锁对不上这篇笔记的锁 ID 时拒绝。
	 */
	public static void require(UUID noteId, String lockId, Screen returnTo, Runnable then) {
		if (get(lockId) != null) {
			then.run();
			return;
		}
		withLock(noteId, data -> {
			if (!"ok".equals(data.status())) {
				PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
				return;
			}
			String id;
			try {
				id = KeyRing.folderId(data.content());
			} catch (IllegalArgumentException e) {
				id = null;
			}
			if (!lockId.equals(id)) {
				PasswordScreen.tell("message.memorypalace.folder_lock_mismatch");
				return;
			}
			Minecraft.getInstance().gui.setScreen(PasswordScreen.unlockFolder(data.folder(), data.content(), returnTo, key -> {
				put(lockId, key);
				then.run();
			}));
		});
	}

	/** 向服务端要这篇笔记所在文件夹的锁文件。 */
	public static void withLock(UUID noteId, Consumer<FolderPayloads.LockData> callback) {
		List<Consumer<FolderPayloads.LockData>> list = WAITING.computeIfAbsent(noteId, k -> new ArrayList<>());
		list.add(callback);
		if (list.size() == 1) {
			ClientPlayNetworking.send(new FolderPayloads.LockRequest(noteId));
		}
	}

	public static void onLockData(FolderPayloads.LockData data) {
		List<Consumer<FolderPayloads.LockData>> callbacks = WAITING.remove(data.noteId());
		if (callbacks != null) {
			callbacks.forEach(cb -> cb.accept(data));
		}
	}

	/** 把锁文件写回服务端（oldContent = 写之前的内容，还没有锁时为 ""）。done(true) = 确认已存盘。 */
	public static void writeLock(String folder, String newContent, String oldContent, Consumer<Boolean> done) {
		int id = ++writeCounter;
		int gen = ClientKeys.generation();
		WRITES.put(id, ok -> done.accept(ok && gen == ClientKeys.generation()));
		ClientPlayNetworking.send(new FolderPayloads.LockWrite(id, folder, oldContent.isEmpty() ? "" : Vault.hash(oldContent), newContent));
	}

	public static void onLockWriteResult(FolderPayloads.LockWriteResult result) {
		Consumer<Boolean> callback = WRITES.remove(result.requestId());
		if (callback == null) {
			return;
		}
		boolean ok = "OK".equals(result.result());
		if (!ok) {
			PasswordScreen.tell(switch (result.result()) {
				case "CONFLICT" -> "message.memorypalace.folder_lock_conflict";
				case "DENIED" -> "command.memorypalace.folder_denied";
				default -> "message.memorypalace.folder_lock_write_failed";
			});
		}
		callback.accept(ok);
	}
}
