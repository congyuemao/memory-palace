package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.CryptoClient;
import com.memorypalace.client.crypto.NoteSaver;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.mc.EditPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * 客户端入口：只在玩家电脑上运行（界面、按键、渲染都放这边）。
 */
public class MemoryPalaceClient implements ClientModInitializer {
	// 按键设置里会出现一个"记忆宫殿"分类
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MemoryPalace.id("main"));

	// 默认 M 键打开笔记窗口，玩家可以在"按键设置"里改
	private static KeyMapping openNoteKey;
	private static KeyMapping settingsKey;

	@Override
	public void onInitializeClient() {
		openNoteKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.memorypalace.open_note", InputConstants.KEY_M, CATEGORY));

		// 设置快捷键默认不绑定（没装 Mod Menu 的玩家可以在"按键设置"里自己绑一个）
		settingsKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.memorypalace.settings", InputConstants.UNKNOWN.getValue(), CATEGORY));

		// 每个游戏刻（每秒 20 次）检查一次按键有没有被按下，顺便检查要不要自动上锁
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openNoteKey.consumeClick()) {
				client.gui.setScreen(new NoteScreen());
			}
			while (settingsKey.consumeClick()) {
				client.gui.setScreen(new SettingsScreen(client.gui.screen()));
			}
			ClientKeys.tick();
		});

		NoteImageStore.register();
		// 笔记正文（分块收齐、加密笔记先解锁）、钥匙圈、加密/解密/上锁都在这里
		CryptoClient.register();

		// 保存结果交给发起保存的那一方；/palace new 之后直接打开编辑器
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.SaveResult.TYPE, (payload, context) -> NoteSaver.onResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.OpenEditor.TYPE, (payload, context) ->
				context.client().gui.setScreen(new EditorScreen(payload.noteId(), Component.literal(payload.title()), payload.body())));

		// JLaTeXMath 第一次用要加载字体，比较慢：游戏启动时在后台先画一个公式"热身"
		Thread.ofVirtual().name("memorypalace-math-warmup").start(() -> MathRenderer.render("x^2", false, 12, 0xFF000000));

		// 事件回调：玩家进入世界（或连上服务器）时执行
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(() -> {
					if (client.player != null) {
						client.player.sendSystemMessage(Component.translatable("message.memorypalace.started"));
					}
				}));
	}
}
