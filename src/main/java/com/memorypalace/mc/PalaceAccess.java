package com.memorypalace.mc;

import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 谁能碰笔记。整个世界一个笔记库，权限按篇算（规则见 NoteAccess）：
 * - 读、改某一篇：看那篇笔记的 palace-owner / palace-read / palace-edit 和世界的"笔记可见性"
 * - 管理笔记库（领书、新建、重扫、改可见性、收件箱）：只有管理员
 * - 钥匙圈和加密：只有本机房主（现在一个笔记库只有一个钥匙圈；每人一个钥匙圈是以后的事）
 * 笔记 ID 不是权限凭证：客户端发什么 ID 都要在服务端重新检查。
 */
public final class PalaceAccess {
	private PalaceAccess() {}

	/** 管理员：单人/局域网的房主，或者服务器上有 2 级（gamemaster）以上权限的 OP。 */
	public static boolean admin(ServerPlayer player) {
		return isHost(player) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	/** 本机房主：自己电脑上开的世界（单人或开放到局域网）的主人。专用服务器上没有房主。 */
	public static boolean isHost(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
	}

	public static NoteAccess.Who who(ServerPlayer player) {
		return new NoteAccess.Who(player.getUUID(), admin(player));
	}

	public static boolean canRead(ServerPlayer player, NoteSummary note) {
		return note.canRead(who(player), PalaceServer.visibility());
	}

	public static boolean canEdit(ServerPlayer player, NoteSummary note) {
		return note.canEdit(who(player));
	}

	/** 管理员才能做的事，不是管理员时顺便告诉原因。 */
	public static boolean checkAdmin(ServerPlayer player) {
		if (admin(player)) {
			return true;
		}
		player.sendSystemMessage(Component.translatable("message.memorypalace.admin_only"));
		return false;
	}
}
