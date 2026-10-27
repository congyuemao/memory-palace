package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆笔记这几种物品。仍然是原版成书（不注册新物品：卸载模组后还是书，没装模组的玩家也能进服），
 * 靠 custom_data 里 {memorypalace: {...}} 的标记区分：
 * - 空白记忆笔记：blank = 1。右键在自己的个人区新建一篇笔记，这本书就变成那篇的笔记书
 * - 空白上锁笔记：blank = 1, locked = 1。同上，新建后马上加密
 * - 空白抄本：blank = 1, copy_of = 原笔记 ID。右键把原笔记抄一份成独立的新笔记
 * - 待上锁的笔记书：id = ..., pending_lock = 1。作者下次右键时在客户端加密（服务端没有钥匙）
 * 贴图靠 custom_model_data 的字符串（见 assets/minecraft/items/written_book.json）：
 * "memorypalace:memory_note"（普通）、"memorypalace:locked_note"（加密的、待上锁的、空白上锁笔记）。
 * 没装模组的客户端没有这份覆盖，照样显示普通成书。
 */
public final class NoteItems {
	private static final String TAG_ROOT = MemoryPalace.MOD_ID;
	static final String MODEL_NOTE = "memorypalace:memory_note";
	static final String MODEL_LOCKED = "memorypalace:locked_note";
	private static final Style LORE = Style.EMPTY.withColor(0xAAAAAA).withItalic(false);

	private NoteItems() {}

	private static CompoundTag tag(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) {
			return new CompoundTag();
		}
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom == null ? new CompoundTag() : custom.copyTag().getCompound(TAG_ROOT).orElse(new CompoundTag());
	}

	private static void update(ItemStack stack, java.util.function.Consumer<CompoundTag> change) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag data = root.getCompound(TAG_ROOT).orElse(new CompoundTag());
			change.accept(data);
			root.put(TAG_ROOT, data);
		});
	}

	private static boolean flag(CompoundTag tag, String key) {
		return tag.getBooleanOr(key, false);
	}

	// ---- 空白笔记 ----

	/** 新的空白记忆笔记（locked = 空白上锁笔记）。 */
	public static ItemStack blank(boolean locked) {
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		update(stack, data -> {
			data.putBoolean("blank", true);
			if (locked) {
				data.putBoolean("locked", true);
			}
		});
		stack.set(DataComponents.ITEM_NAME, Component.literal(locked ? "上锁笔记（空白）" : "记忆笔记（空白）"));
		stack.set(DataComponents.LORE, lore(locked ? "右键：新建一篇加密笔记" : "右键：新建一篇笔记"));
		setModel(stack, locked);
		return stack;
	}

	/** 空白抄本：右键把 source 这篇抄一份。 */
	public static ItemStack copyBlank(UUID source) {
		ItemStack stack = blank(false);
		update(stack, data -> data.putString("copy_of", source.toString()));
		stack.set(DataComponents.ITEM_NAME, Component.literal("记忆笔记抄本（空白）"));
		stack.set(DataComponents.LORE, lore("右键：把原笔记抄成一篇独立的新笔记", "（只有读得了原笔记的人能抄）"));
		return stack;
	}

	/** 是不是空白笔记（含空白上锁笔记、空白抄本）。 */
	public static boolean isBlank(ItemStack stack) {
		return flag(tag(stack), "blank");
	}

	/** 普通的空白记忆笔记（不是上锁的，也不是抄本）：配方里当材料用。 */
	public static boolean isPlainBlank(ItemStack stack) {
		CompoundTag tag = tag(stack);
		return flag(tag, "blank") && !flag(tag, "locked") && !tag.contains("copy_of");
	}

	public static boolean isBlankLocked(ItemStack stack) {
		CompoundTag tag = tag(stack);
		return flag(tag, "blank") && flag(tag, "locked");
	}

	public static Optional<UUID> copyOf(ItemStack stack) {
		CompoundTag tag = tag(stack);
		if (!flag(tag, "blank")) {
			return Optional.empty();
		}
		return tag.getString("copy_of").flatMap(s -> {
			try {
				return Optional.of(UUID.fromString(s));
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}
		});
	}

	// ---- 抄进被锁文件夹（见 FolderCopy） ----

	/**
	 * 抄写中的记录 ID（服务端 CopyJobs 里的那一条）；不在抄写中 → empty。
	 * 只是个"去服务端查哪条"的号码：物品数据可以伪造，目标、指纹等一律以服务端的记录为准。
	 */
	public static Optional<UUID> copyJob(ItemStack stack) {
		CompoundTag tag = tag(stack);
		if (!flag(tag, "blank")) {
			return Optional.empty();
		}
		return tag.getString("copy_job").flatMap(s -> {
			try {
				return Optional.of(UUID.fromString(s));
			} catch (IllegalArgumentException e) {
				return Optional.empty();
			}
		});
	}

	/** 记下"正在按这条记录抄写"（物品不消耗，抄好了才换成笔记书；中途取消可以再右键接着抄）。 */
	static void setCopyJob(ItemStack stack, UUID job) {
		update(stack, data -> data.putString("copy_job", job.toString()));
		stack.set(DataComponents.LORE, lore("抄写中：内容要用文件夹密码加密后才算抄好", "没抄完可以再右键继续"));
	}

	static void clearCopyJob(ItemStack stack) {
		if (stack.isEmpty() || copyJob(stack).isEmpty()) {
			return;
		}
		update(stack, data -> data.remove("copy_job"));
		stack.set(DataComponents.LORE, lore("右键：把原笔记抄成一篇独立的新笔记", "（只有读得了原笔记的人能抄）"));
	}

	// ---- 待上锁 ----

	public static boolean pendingLock(ItemStack stack) {
		return flag(tag(stack), "pending_lock");
	}

	/** 这本笔记书的待上锁版本（一本）。 */
	public static ItemStack withPendingLock(ItemStack bound) {
		ItemStack stack = bound.copyWithCount(1);
		update(stack, data -> data.putBoolean("pending_lock", true));
		stack.set(DataComponents.LORE, lore("待上锁：作者下次右键时加密"));
		setModel(stack, true);
		return stack;
	}

	/** 去掉待上锁标记（已经加密好了）。@return 有没有改动 */
	static boolean clearPendingLock(ItemStack stack) {
		if (!pendingLock(stack)) {
			return false;
		}
		update(stack, data -> data.remove("pending_lock"));
		stack.remove(DataComponents.LORE);
		return true;
	}

	// ---- 贴图 ----

	/** 是不是画成上锁笔记。 */
	static boolean lockedModel(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && MODEL_LOCKED.equals(data.getString(0));
	}

	/** 设贴图。@return 有没有改动 */
	static boolean setModel(ItemStack stack, boolean locked) {
		String want = locked ? MODEL_LOCKED : MODEL_NOTE;
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (data != null && want.equals(data.getString(0))) {
			return false;
		}
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(want), List.of()));
		return true;
	}

	private static ItemLore lore(String... lines) {
		return new ItemLore(java.util.Arrays.stream(lines).map(l -> (Component) Component.literal(l).withStyle(LORE)).toList());
	}
}
