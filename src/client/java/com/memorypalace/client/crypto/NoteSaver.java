package com.memorypalace.client.crypto;

import com.memorypalace.mc.EditPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 把一篇笔记的正文（明文或密文，服务端不关心）分块发给服务端保存，结果回来时调用回调。
 * 编辑器保存、加密、解除加密都走这里。只在渲染线程使用。
 */
public final class NoteSaver {
	private static int session;
	private static final Map<Integer, Consumer<EditPayloads.SaveResult>> CALLBACKS = new HashMap<>();

	private NoteSaver() {}

	/**
	 * @param stored       要写进文件的正文（加密笔记就是密文）
	 * @param expectedHash 打开时文件正文的指纹；null = 强制覆盖
	 */
	public static void save(UUID noteId, String stored, String expectedHash, Consumer<EditPayloads.SaveResult> callback) {
		send(noteId, stored, expectedHash == null ? "" : expectedHash, callback);
	}

	/** 解除加密：把明文写回。服务端只有看到这个标记（并且是本机房主）才允许加密笔记变成明文。 */
	public static void saveDecrypted(UUID noteId, String plain, String expectedHash, Consumer<EditPayloads.SaveResult> callback) {
		send(noteId, plain, EditPayloads.DECRYPT_PREFIX + expectedHash, callback);
	}

	/** 还原历史版本：服务端看到这个标记，会把还原前的样子一定留一份历史（可以反悔）。 */
	public static void saveRestored(UUID noteId, String stored, String expectedHash, Consumer<EditPayloads.SaveResult> callback) {
		send(noteId, stored, EditPayloads.RESTORE_PREFIX + expectedHash, callback);
	}

	private static void send(UUID noteId, String stored, String guard, Consumer<EditPayloads.SaveResult> callback) {
		int id = ++session;
		CALLBACKS.put(id, callback);
		List<String> parts = chunks(stored);
		for (int i = 0; i < parts.size(); i++) {
			ClientPlayNetworking.send(new EditPayloads.SaveChunk(noteId, id, i, parts.size(), guard, parts.get(i)));
		}
	}

	/** 离开世界：没回来的保存不会再回来了，丢掉回调（它们引用着编辑器和明文）。 */
	public static void clear() {
		CALLBACKS.clear();
	}

	public static void onResult(EditPayloads.SaveResult result) {
		Consumer<EditPayloads.SaveResult> callback = CALLBACKS.remove(result.session());
		if (callback != null) {
			callback.accept(result);
		}
	}

	/** 按 CHUNK_CHARS 切块，不把 emoji 之类的"代理对"切成两半。空正文也要发一块。 */
	private static List<String> chunks(String body) {
		List<String> out = new ArrayList<>();
		int i = 0;
		do {
			int end = Math.min(body.length(), i + EditPayloads.CHUNK_CHARS);
			if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) {
				end--;
			}
			out.add(body.substring(i, end));
			i = end;
		} while (i < body.length());
		return out;
	}
}
