package com.memorypalace.mc;

import com.memorypalace.core.vault.NoteAccess;
import com.memorypalace.core.vault.NoteSummary;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * 谁能碰笔记。整个世界一个笔记库，权限按篇算（规则见 NoteAccess）：
 * - 多人总开关（PalaceConfig.multiplayer）关着：只有本机房主能用，其他人一律当作"没有身份"，什么都读不了
 * - 读、改某一篇：看那篇笔记的 palace-owner / palace-read / palace-edit 和世界的"笔记可见性"
 * - 新建、领书、分享、管理：看权限节点 memorypalace.*（fabric-permissions-api：
 *   装了 LuckPerms 等权限模组就按它的设置；没装时管理员 = 2 级 OP，其他节点所有人都有）
 * - 钥匙圈和加密：只有本机房主（现在一个笔记库只有一个钥匙圈；每人一个钥匙圈是以后的事）
 * 笔记 ID 不是权限凭证：客户端发什么 ID 都要在服务端重新检查。
 */
public final class PalaceAccess {
	/** 权限节点：管理（领任何书、在任何位置新建、重扫、改可见性、重读配置）。默认 2 级 OP。 */
	public static final String ADMIN = "memorypalace.admin";
	/** 权限节点：在自己的个人区新建笔记。默认所有人。 */
	public static final String CREATE = "memorypalace.create";
	/** 权限节点：给自己领读得了的笔记的书。默认所有人。 */
	public static final String GIVE = "memorypalace.give";
	/** 权限节点：把自己的笔记分享给别人。默认所有人。 */
	public static final String SHARE = "memorypalace.share";

	private PalaceAccess() {}

	/** 本机房主：自己电脑上开的世界（单人或开放到局域网）的主人。专用服务器上没有房主。 */
	public static boolean isHost(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
	}

	/** 这个人能不能用笔记功能：房主永远能；其他人要多人总开关打开。 */
	public static boolean enabled(ServerPlayer player) {
		return isHost(player) || PalaceConfig.get().multiplayer();
	}

	/** 管理员：房主，或者有 memorypalace.admin 权限（没装权限模组时 = 2 级 OP）。 */
	public static boolean admin(ServerPlayer player) {
		return enabled(player) && (isHost(player) || Permissions.check(player, ADMIN, PermissionLevel.GAMEMASTERS));
	}

	/** 有没有某个权限节点（CREATE、GIVE、SHARE）：管理员都有；其他人默认有，权限模组可以收回。 */
	public static boolean allowed(ServerPlayer player, String node) {
		return enabled(player) && (admin(player) || Permissions.check(player, node, true));
	}

	/** 命令用：执行命令的是玩家、并且有这个权限节点。 */
	public static boolean allowed(CommandSourceStack source, String node) {
		ServerPlayer player = source.getPlayer();
		return player != null && allowed(player, node);
	}

	/**
	 * 无主笔记（单人时代的笔记、房主建的笔记、收件箱）算谁的：有房主的世界只算房主的；
	 * 专用服务器上没有房主，算管理员的。局域网里被授予 memorypalace.admin 的人管得了命令，但读不到房主的笔记。
	 */
	public static NoteAccess.Who who(ServerPlayer player) {
		if (!enabled(player)) {
			return NoteAccess.Who.NOBODY;
		}
		boolean ownsOrphans = isHost(player) || player.level().getServer().isDedicatedServer() && admin(player);
		return new NoteAccess.Who(player.getUUID(), ownsOrphans);
	}

	public static boolean canRead(ServerPlayer player, NoteSummary note) {
		return note.canRead(who(player), PalaceServer.visibility());
	}

	public static boolean canEdit(ServerPlayer player, NoteSummary note) {
		return note.canEdit(who(player));
	}

	/**
	 * 书名、准星提示能不能用正文标题。书是物品，谁拿到都能看书名、书页，和多人总开关无关
	 * （总开关关着时，局域网访客也能从书架上把书取走）。所以：
	 * - 纯单人游戏（没开放局域网）：只有房主，和以前一样显示（收件箱、加密笔记除外）
	 * - 只要可能有别人（开放了局域网、专用服务器）：只有世界里人人能读的笔记才显示
	 */
	public static boolean titleIsPublic(NoteSummary note) {
		return PalaceServer.othersCanJoin() ? note.titleIsPublic(PalaceServer.visibility()) : !note.ownerOnly();
	}
}
