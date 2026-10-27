package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 记忆笔记的合成配方（无序合成，和原版复制成书一样是"特殊配方"：材料是带标记的书，普通配方表达不了；
 * 代价是配方书里不显示，写在 README 里）：
 * - 记忆笔记（空白）= 书与笔 + 青金石
 * - 上锁笔记（空白）= 空白记忆笔记 + 铁锭 + 绊线钩
 * - 给已有笔记上锁 = 笔记书 + 铁锭 + 绊线钩 → 标成"待上锁"，作者下次右键时在客户端加密（服务端不能加密）
 * - 抄本 = 笔记书 + 空白记忆笔记 → 空白抄本（右键时抄成独立的新笔记），原来的笔记书留在合成格里
 * 配方 JSON 在 data/memorypalace/recipe/ 下，只写 type。
 */
public final class NoteRecipes {
	private NoteRecipes() {}

	/** 合成格里的东西是不是正好对上这几样（每样一个，顺序随意）。 */
	static boolean exactly(CraftingInput input, List<Predicate<ItemStack>> wanted) {
		List<Predicate<ItemStack>> left = new ArrayList<>(wanted);
		for (ItemStack stack : input.items()) {
			if (stack.isEmpty()) {
				continue;
			}
			Predicate<ItemStack> hit = left.stream().filter(p -> p.test(stack)).findFirst().orElse(null);
			if (hit == null) {
				return false;
			}
			left.remove(hit);
		}
		return left.isEmpty();
	}

	/** 合成格里第一个满足条件的东西。 */
	static ItemStack find(CraftingInput input, Predicate<ItemStack> which) {
		return input.items().stream().filter(s -> !s.isEmpty() && which.test(s)).findFirst().orElse(ItemStack.EMPTY);
	}

	static boolean bound(ItemStack stack) {
		return stack.is(Items.WRITTEN_BOOK) && NoteBooks.idOf(stack).isPresent();
	}

	public static final class BlankNote extends CustomRecipe {
		static final BlankNote INSTANCE = new BlankNote();
		static final RecipeSerializer<BlankNote> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

		@Override
		public boolean matches(CraftingInput input, Level level) {
			return exactly(input, List.of(s -> s.is(Items.WRITABLE_BOOK), s -> s.is(Items.LAPIS_LAZULI)));
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			return NoteItems.blank(false);
		}

		@Override
		public RecipeSerializer<BlankNote> getSerializer() {
			return SERIALIZER;
		}
	}

	public static final class LockedBlankNote extends CustomRecipe {
		static final LockedBlankNote INSTANCE = new LockedBlankNote();
		static final RecipeSerializer<LockedBlankNote> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

		@Override
		public boolean matches(CraftingInput input, Level level) {
			return exactly(input, List.of(NoteItems::isPlainBlank, s -> s.is(Items.IRON_INGOT), s -> s.is(Items.TRIPWIRE_HOOK)));
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			return NoteItems.blank(true);
		}

		@Override
		public RecipeSerializer<LockedBlankNote> getSerializer() {
			return SERIALIZER;
		}
	}

	public static final class LockNote extends CustomRecipe {
		static final LockNote INSTANCE = new LockNote();
		static final RecipeSerializer<LockNote> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

		// 已经加密的（画成上锁笔记的）、已经待上锁的不用再锁
		private static boolean lockable(ItemStack stack) {
			return bound(stack) && !NoteItems.pendingLock(stack) && !NoteItems.lockedModel(stack);
		}

		@Override
		public boolean matches(CraftingInput input, Level level) {
			return exactly(input, List.of(LockNote::lockable, s -> s.is(Items.IRON_INGOT), s -> s.is(Items.TRIPWIRE_HOOK)));
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			return NoteItems.withPendingLock(find(input, LockNote::lockable));
		}

		@Override
		public RecipeSerializer<LockNote> getSerializer() {
			return SERIALIZER;
		}
	}

	public static final class CopyNote extends CustomRecipe {
		static final CopyNote INSTANCE = new CopyNote();
		static final RecipeSerializer<CopyNote> SERIALIZER = new RecipeSerializer<>(MapCodec.unit(INSTANCE), StreamCodec.unit(INSTANCE));

		@Override
		public boolean matches(CraftingInput input, Level level) {
			return exactly(input, List.of(NoteRecipes::bound, NoteItems::isPlainBlank));
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			return NoteBooks.idOf(find(input, NoteRecipes::bound)).map(NoteItems::copyBlank).orElse(ItemStack.EMPTY);
		}

		/** 原来的笔记书留下（和原版复制成书一样）。 */
		@Override
		public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
			NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
			for (int i = 0; i < input.size(); i++) {
				ItemStack stack = input.getItem(i);
				if (bound(stack)) {
					remaining.set(i, stack.copyWithCount(1));
					break;
				}
			}
			return remaining;
		}

		@Override
		public RecipeSerializer<CopyNote> getSerializer() {
			return SERIALIZER;
		}
	}

	public static void register() {
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, MemoryPalace.id("memory_note"), BlankNote.SERIALIZER);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, MemoryPalace.id("locked_memory_note"), LockedBlankNote.SERIALIZER);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, MemoryPalace.id("lock_note"), LockNote.SERIALIZER);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, MemoryPalace.id("copy_note"), CopyNote.SERIALIZER);
	}
}
