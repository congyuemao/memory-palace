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
		String title = note.title().length() > MAX_TITLE ? note.title().substring(0, MAX_TITLE - 1) + "…" : note.title();
		Component page = Component.literal("记忆宫殿笔记书\n\n对应笔记：\n" + note.relativePath()
				+ "\n\n（装了 Memory Palace 模组后右键即可阅读）");
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough(title), "记忆宫殿", 0, List.of(Filterable.passThrough(page)), true));

		CompoundTag data = new CompoundTag();
		data.putString(TAG_ID, note.id().toString());
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG_ROOT, data));
		return stack;
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
