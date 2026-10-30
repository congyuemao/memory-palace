package com.memorypalace.client;

import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.CryptoClient;
import com.memorypalace.client.crypto.InboxCapture;
import com.memorypalace.client.crypto.NoteSaver;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.CapturePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

/**
 * 客户端入口：只在玩家电脑上运行（界面、按键、渲染都放这边）。
 */
public class MemoryPalaceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 按键：M 打开笔记窗口、N 快速收集、J 找笔记、设置（默认不绑）。都可以在"按键绑定"里改
		PalaceKeys.register();

		// 每个游戏刻（每秒 20 次）：检查按键、准星对准的书、导航、要不要自动上锁
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (PalaceKeys.openNote.consumeClick()) {
				client.gui.setScreen(new NoteScreen());
			}
			while (PalaceKeys.capture.consumeClick()) {
				client.gui.setScreen(InboxCapture.screen()); // 有没保存成功的随手记就先找回它
			}
			while (PalaceKeys.search.consumeClick()) {
				client.gui.setScreen(new SearchScreen());
			}
			while (PalaceKeys.settings.consumeClick()) {
				client.gui.setScreen(new SettingsScreen(client.gui.screen()));
			}
			LookHud.tick(client);
			Navigator.tick(client);
			ClientKeys.tick();
			InboxCapture.tick();
		});

		NoteImageStore.register();
		// 笔记正文（分块收齐、加密笔记先解锁）、钥匙圈、加密/解密/上锁都在这里
		CryptoClient.register();
		LookHud.register();
		Navigator.register();
		SearchScreen.register();

		// 保存结果交给发起保存的那一方；/palace new 之后直接打开编辑器
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.SaveResult.TYPE, (payload, context) -> NoteSaver.onResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(EditPayloads.OpenEditor.TYPE, (payload, context) -> CryptoClient.openEditor(payload));
		ClientPlayNetworking.registerGlobalReceiver(CapturePayloads.CaptureResult.TYPE, (payload, context) -> InboxCapture.onResult(payload));
		// 笔记链接：阅读界面的断链和反向链接、编辑器里 [[ 的补全
		ClientPlayNetworking.registerGlobalReceiver(com.memorypalace.mc.LinkPayloads.Info.TYPE, (payload, context) -> NoteScreen.onLinkInfo(payload));
		ClientPlayNetworking.registerGlobalReceiver(com.memorypalace.mc.LinkPayloads.Suggestions.TYPE, (payload, context) -> EditorScreen.onSuggestions(payload));

		// JLaTeXMath 第一次用要加载字体，比较慢：游戏启动时在后台先画一个公式"热身"
		Thread.ofVirtual().name("memorypalace-math-warmup").start(() -> MathRenderer.render("x^2", false, 12, 0xFF000000));

		// 进入世界（或连上服务器）时：打招呼，并检查按键有没有和别的（原版、其他模组）冲突
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(() -> {
					if (client.player != null) {
						client.player.sendSystemMessage(Component.translatable("message.memorypalace.started"));
						PalaceKeys.warnConflicts();
						InboxCapture.remind();
					}
				}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Navigator::stop));
	}
}
