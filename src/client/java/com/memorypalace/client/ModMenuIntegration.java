package com.memorypalace.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * 装了 Mod Menu 时，模组列表里"记忆宫殿"旁边会出现设置按钮，点开就是 SettingsScreen。
 * 没装 Mod Menu 时这个类不会被加载，不影响运行。
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
