package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

		// 每个游戏刻（每秒 20 次）检查一次按键有没有被按下
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openNoteKey.consumeClick()) {
				client.gui.setScreen(new NoteScreen());
			}
			while (settingsKey.consumeClick()) {
				client.gui.setScreen(new SettingsScreen(client.gui.screen()));
			}
		});

		NoteImageStore.register();

		// 编辑器：保存结果转给正开着的编辑器；/palace new 之后直接打开编辑器
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.SaveResult.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof EditorScreen editor && payload.noteId().equals(editor.noteId())) {
				editor.onSaveResult(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.OpenEditor.TYPE, (payload, context) ->
				context.client().gui.setScreen(new EditorScreen(payload.noteId(), Component.literal(payload.title()), payload.body())));
		// JLaTeXMath 第一次用要加载字体，比较慢：游戏启动时在后台先画一个公式"热身"
		Thread.ofVirtual().name("memorypalace-math-warmup").start(() -> MathRenderer.render("x^2", false, 12, 0xFF000000));

		// 服务端说"打开这篇笔记" → 打开笔记窗口；"刷新" → 只在还开着这篇时更新内容
		ClientPlayNetworking.registerGlobalReceiver(OpenNotePayload.TYPE, (payload, context) -> {
			var gui = context.client().gui;
			if (payload.refresh()) {
				if (gui.screen() instanceof NoteScreen open && payload.id().equals(open.noteId())) {
					open.updateBody(payload.body());
				}
				return;
			}
			gui.setScreen(new NoteScreen(payload.id(), Component.literal(payload.title()), payload.body()));
		});

		// 事件回调：玩家进入世界（或连上服务器）时执行
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(() -> {
					if (client.player != null) {
						client.player.sendSystemMessage(Component.translatable("message.memorypalace.started"));
					}
				}));
	}
}
