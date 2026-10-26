package com.memorypalace.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 谁能碰笔记库。现在整个世界只有一个笔记库，没有"这篇笔记属于谁"的概念，所以一律只允许本机房主：
 * 单人游戏里就是你自己；开放到局域网、专用服务器上，其他玩家的客户端发什么消息都没用（不能读、搜、写、查书名）。
 * 以后做多人版时，要换成每个玩家自己的笔记库，或者按笔记记录所有者和权限。
 */
public final class PalaceAccess {
	private PalaceAccess() {}

	public static boolean allowed(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return !server.isDedicatedServer() && !server.isPublished() && server.isSingleplayerOwner(player.nameAndId());
	}

	/** 不允许时顺便告诉玩家原因（右键笔记书、用命令时）。 */
	public static boolean check(ServerPlayer player) {
		if (allowed(player)) {
			return true;
		}
		player.sendSystemMessage(Component.translatable("message.memorypalace.host_only"));
		return false;
	}
}
