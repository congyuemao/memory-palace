package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteSummary;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "笔记书" = 原版成书 + custom_data 里的 {memorypalace: {id: "..."}}。
 * 卸载模组后它还是一本普通的书，书页上写着对应的文件名。
 */
public final class NoteBooks {
	private static final String TAG_ROOT = MemoryPalace.MOD_ID;
	private static final String TAG_ID = "id";
	// 原版成书标题最长 32 个字符
	private static final int MAX_TITLE = 32;

	private NoteBooks() {}

	public static ItemStack create(NoteSummary note) {
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		setContent(stack, note);
		CompoundTag data = new CompoundTag();
		data.putString(TAG_ID, note.id().toString());
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG_ROOT, data));
		NoteItems.setModel(stack, note.encrypted());
		return stack;
	}

	/**
	 * 书名。原版成书的书名、书页、作者谁拿着书都看得到（多人游戏里别人看不到服务器硬盘，路径也算秘密），所以：
	 * - 世界里人人能读的笔记：标题，书页写文件路径，作者写笔记的主人
	 * - 加密笔记：一律同上。标题就是文件名（正文标题在密文里），文件名本来就不加密；捡到书的人知道名字也打不开
	 * - 其余不是人人能读的明文笔记（收件箱、只给部分人看的、"默认私有"世界里的）："【私密笔记】#7f3a"。
	 *   不写标题也不写路径，只带 ID 的前 4 位，方便分清是哪几本；读得了的人对准书架时能看到真正的标题
	 * 原版书名最长 32 字，超出用"…"截断。
	 */
	static String titleFor(NoteSummary note) {
		String tag = " #" + note.id().toString().substring(0, 4);
		String title = isPublic(note) ? note.title() : "【私密笔记】" + tag;
		return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title;
	}

	private static boolean isPublic(NoteSummary note) {
		return note.encrypted() || PalaceAccess.titleIsPublic(note);
	}

	/** 书的作者：笔记的主人（没写主人的归房主，专用服务器上归管理员）。不公开的笔记不写是谁的。 */
	static String authorFor(NoteSummary note) {
		if (!isPublic(note)) {
			return DEFAULT_AUTHOR;
		}
		java.util.UUID owner = note.access().owner();
		return (owner == null ? PalaceServer.hostName() : PalaceServer.playerName(owner)).orElse(DEFAULT_AUTHOR);
	}

	private static final String DEFAULT_AUTHOR = "记忆宫殿";

	private static Component pageFor(NoteSummary note) {
		String where = isPublic(note) ? "对应笔记：\n" + note.relativePath() : "这是一篇不公开的笔记，只有有权限的人能打开。";
		return Component.literal("记忆宫殿笔记书\n\n" + where + "\n\n（装了 Memory Palace 模组后右键即可阅读）");
	}

	private static void setContent(ItemStack stack, NoteSummary note) {
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough(titleFor(note)), authorFor(note), 0, List.of(Filterable.passThrough(pageFor(note))), true));
	}

	/**
	 * 书名和笔记现状对不上就改过来（笔记改了标题、或者被加密了——加密前领的书，书名还是正文标题，会留在存档里）。
	 * @return 有没有改动（改了的话，书在方块里时调用方要 setChanged）
	 */
	public static boolean refresh(ItemStack stack, NoteSummary note) {
		// 贴图跟着笔记走：加密了（或者等着上锁）画成上锁笔记；已经加密好了就不再"待上锁"
		boolean changed = note.encrypted() && NoteItems.clearPendingLock(stack);
		changed |= NoteItems.setModel(stack, note.encrypted() || NoteItems.pendingLock(stack));
		// 书名和书页都要对得上：同一个书名下，书页里的路径也可能已经过时（比如笔记改成了私密）
		WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (content != null && content.title().raw().equals(titleFor(note)) && content.author().equals(authorFor(note))
				&& content.pages().size() == 1 && content.pages().getFirst().raw().equals(pageFor(note))) {
			return changed;
		}
		setContent(stack, note);
		return true;
	}

	/**
	 * 在线玩家背包里这篇笔记的书都刷新一遍（加密了、改了标题、改名之后调用）：贴图、书名马上跟着变，不用等右键。
	 */
	public static void refreshCarried(net.minecraft.server.MinecraftServer server, NoteSummary note) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			var inventory = player.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				if (idOf(stack).filter(note.id()::equals).isPresent()) {
					refresh(stack, note);
				}
			}
		}
	}

	/** 这本书对应的笔记 ID；不是笔记书时为 empty。 */
	public static Optional<UUID> idOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return Optional.empty();
		}
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) {
			return Optional.empty();
		}
		return custom.copyTag().getCompound(TAG_ROOT)
				.flatMap(tag -> tag.getString(TAG_ID))
				.flatMap(NoteBooks::parseUuid);
	}

	private static Optional<UUID> parseUuid(String s) {
		try {
			return Optional.of(UUID.fromString(s));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
