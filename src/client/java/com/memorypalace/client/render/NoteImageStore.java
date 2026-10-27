package com.memorypalace.client.render;

import com.memorypalace.core.render.NoteImages;
import com.memorypalace.mc.ImagePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端的图片仓库：第一次要某张图时向服务端请求，图到了就变成贴图并通知界面重新排版。
 * 只在渲染线程使用。
 */
public final class NoteImageStore {
	public enum State { LOADING, READY, FAILED }

	private static final Map<String, State> STATES = new HashMap<>();
	private static Runnable onArrive = () -> {};

	private NoteImageStore() {}

	private static String key(UUID noteId, String src) {
		return "img|" + noteId + "|" + src;
	}

	/** 图片当前状态；还没请求过就发请求并返回 LOADING。 */
	public static State state(UUID noteId, String src) {
		String key = key(noteId, src);
		State state = STATES.get(key);
		if (state == State.READY && RasterTextures.get(key) == null) {
			state = null; // 贴图被缓存挤掉了，重新要
		}
		if (state == null) {
			STATES.put(key, State.LOADING);
			ClientPlayNetworking.send(new ImagePayloads.Request(noteId, src));
			return State.LOADING;
		}
		return state;
	}

	public static RasterTextures.Tex texture(UUID noteId, String src) {
		return RasterTextures.get(key(noteId, src));
	}

	/** 有图片到达时要做的事（笔记窗口设置成"重新排版"）。 */
	public static void setOnArrive(Runnable callback) {
		onArrive = callback == null ? () -> {} : callback;
	}

	/** 笔记内容变了（热重载）：忘掉这篇笔记的图片状态，下次重新请求，这样外部换了图也能看到。 */
	public static void forget(UUID noteId) {
		STATES.keySet().removeIf(k -> k.startsWith("img|" + noteId + "|"));
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ImagePayloads.Data.TYPE, (payload, context) -> {
			String key = key(payload.noteId(), payload.src());
			// 只收自己正在等的图：服务端主动塞过来的一律不解码
			if (STATES.get(key) != State.LOADING) {
				return;
			}
			var raster = payload.png().length == 0 ? null : NoteImages.fromBytes(payload.png()).orElse(null);
			if (raster == null) {
				STATES.put(key, State.FAILED);
			} else {
				RasterTextures.put(key, raster, 0);
				STATES.put(key, State.READY);
			}
			onArrive.run();
		});
	}
}
