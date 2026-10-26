package com.memorypalace.client;

import com.memorypalace.MemoryPalace;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆宫殿的所有按键。都注册在原版按键系统里，可以在"选项 → 控制 → 按键绑定 → 记忆宫殿"里随意修改。
 * 默认键都是原版没用的；但其他模组用什么键没法预知，所以进世界时会检查冲突并提醒。
 */
public final class PalaceKeys {
	// 按键设置里会出现一个"记忆宫殿"分类
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MemoryPalace.id("main"));

	public static KeyMapping openNote;
	public static KeyMapping capture;
	public static KeyMapping search;
	public static KeyMapping settings;

	private PalaceKeys() {}

	static void register() {
		openNote = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.memorypalace.open_note", InputConstants.KEY_M, CATEGORY));
		// K：原版（包括 F3 调试组合键）完全没用的键
		capture = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.memorypalace.capture", InputConstants.KEY_K, CATEGORY));
		search = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.memorypalace.search", InputConstants.KEY_J, CATEGORY));
		// 设置快捷键默认不绑定（没装 Mod Menu 的玩家可以自己绑一个）
		settings = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.memorypalace.settings", InputConstants.UNKNOWN.getValue(), CATEGORY));
	}

	public static List<KeyMapping> all() {
		return List.of(openNote, capture, search, settings);
	}

	/**
	 * 和 key 绑在同一个键上的其他按键（原版的、其他模组的、记忆宫殿自己的）。没绑键的不算冲突。
	 * F3 调试组合键（F3+N 等）不算：它们要按住 F3 才触发，单独按那个字母并不会同时触发。
	 */
	public static List<KeyMapping> conflictsOf(KeyMapping key) {
		List<KeyMapping> out = new ArrayList<>();
		if (key.isUnbound()) {
			return out;
		}
		for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
			if (other != key && !other.isUnbound() && other.getCategory() != KeyMapping.Category.DEBUG && key.same(other)) {
				out.add(other);
			}
		}
		return out;
	}

	/** 进世界时调用：有冲突就在聊天栏提醒一次，说清楚是哪个键、和谁冲突、去哪改。 */
	static void warnConflicts() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		for (KeyMapping key : all()) {
			for (KeyMapping other : conflictsOf(key)) {
				mc.player.sendSystemMessage(Component.translatable("message.memorypalace.key_conflict",
						Component.translatable(key.getName()), key.getTranslatedKeyMessage(),
						Component.translatable(other.getName())));
			}
		}
	}
}
