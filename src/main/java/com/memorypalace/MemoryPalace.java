package com.memorypalace;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组的"主入口"：游戏启动、加载模组时，Fabric 会调用 onInitialize()。
 * 这里放客户端和服务端都需要的东西（以后：笔记书物品、/palace 命令）。
 */
public class MemoryPalace implements ModInitializer {
	public static final String MOD_ID = "memorypalace";

	// 往游戏日志里写字用；日志在 run/logs/latest.log
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Memory Palace 已加载");
	}

	/** 生成 "memorypalace:xxx" 这种带命名空间的 ID，注册物品、按键时会用到。 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
