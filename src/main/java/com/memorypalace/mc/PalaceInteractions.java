package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.vault.NoteLocations;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 右键交互：
 * - 手里拿着笔记书右键 → 打开笔记（代替原版的看书界面）
 * - 对着雕纹书架上插着笔记书的那一格右键 → 打开笔记；潜行 + 右键 → 原版行为（取出书）
 */
public final class PalaceInteractions {
	// 再大的笔记，排版和渲染都会很慢；超过这个大小请拆分（约 70 万汉字 / 200 万字母）
	private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

	private PalaceInteractions() {}

	public static void register() {
		NoteWatcher.register();

		// 用 ItemEvents.USE 而不是 UseItemCallback：它在原版切换快捷栏同步之后才触发，
		// 快速切换物品栏再右键时不会打开上一格的书。返回 null = 不处理，交给原版
		ItemEvents.USE.register((level, player, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			Optional<UUID> id = NoteBooks.idOf(stack);
			if (id.isEmpty()) {
				return null;
			}
			// 客户端也返回 SUCCESS，挡住原版在客户端直接打开看书界面
			if (player instanceof ServerPlayer serverPlayer) {
				refreshBook(stack, id.get());
				openNote(serverPlayer, id.get());
			}
			return InteractionResult.SUCCESS;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			var state = level.getBlockState(hit.getBlockPos());

			// 讲台：右键打开上面的笔记书；潜行 + 右键走原版（原版界面里有"取书"按钮）
			if (state.getBlock() instanceof LecternBlock) {
				if (player.isSecondaryUseActive()
						|| !(player instanceof ServerPlayer serverPlayer)
						|| !(level.getBlockEntity(hit.getBlockPos()) instanceof LecternBlockEntity lectern)) {
					return InteractionResult.PASS;
				}
				Optional<UUID> id = NoteBooks.idOf(lectern.getBook());
				if (id.isEmpty()) {
					return InteractionResult.PASS;
				}
				if (refreshBook(lectern.getBook(), id.get())) {
					lectern.setChanged();
				}
				openNote(serverPlayer, id.get());
				recordLocation(level, hit.getBlockPos(), NoteLocations.LECTERN_SLOT, id.get());
				return InteractionResult.SUCCESS;
			}

			if (!(state.getBlock() instanceof ChiseledBookShelfBlock shelf)) {
				return InteractionResult.PASS;
			}

			// 潜行 + 右键有书的格子 = 取书，手里拿什么都行。
			// （原版潜行时只要手里有东西就跳过方块交互，所以这里自己取）
			if (player.isSecondaryUseActive()) {
				OptionalInt slot = shelf.getHitSlot(hit, state.getValue(HorizontalDirectionalBlock.FACING));
				// 格子有没有书记在方块状态里，客户端也看得到
				if (slot.isEmpty() || !state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot.getAsInt()))) {
					return InteractionResult.PASS;
				}
				if (level.getBlockEntity(hit.getBlockPos()) instanceof ChiseledBookShelfBlockEntity shelfEntity
						&& !level.isClientSide()) {
					takeBook(level, hit.getBlockPos(), player, shelfEntity, slot.getAsInt());
				}
				return InteractionResult.SUCCESS;
			}

			// 手里拿着书是想往书架里放书，交给原版。
			// 拿着别的东西（剑、镐）时不交给原版，否则原版会把书抽出来
			if (player.getMainHandItem().is(ItemTags.BOOKSHELF_BOOKS)) {
				return InteractionResult.PASS;
			}
			// 客户端看不到书架里有什么书（原版不同步），所以由服务端判断
			if (!(player instanceof ServerPlayer serverPlayer)
					|| !(level.getBlockEntity(hit.getBlockPos()) instanceof ChiseledBookShelfBlockEntity shelfEntity)) {
				return InteractionResult.PASS;
			}
			OptionalInt slot = shelf.getHitSlot(hit, state.getValue(HorizontalDirectionalBlock.FACING));
			if (slot.isEmpty()) {
				return InteractionResult.PASS;
			}
			Optional<UUID> id = NoteBooks.idOf(shelfEntity.getItem(slot.getAsInt()));
			if (id.isEmpty()) {
				return InteractionResult.PASS;
			}
			if (refreshBook(shelfEntity.getItem(slot.getAsInt()), id.get())) {
				shelfEntity.setChanged();
			}
			openNote(serverPlayer, id.get());
			recordLocation(level, hit.getBlockPos(), slot.getAsInt(), id.get());
			return InteractionResult.SUCCESS;
		});
	}

	/** 书名跟笔记现状对不上（改了标题、被加密了）就顺手改掉。 */
	private static boolean refreshBook(ItemStack stack, UUID id) {
		Vault vault = PalaceServer.vault();
		return vault != null && vault.find(id).map(note -> NoteBooks.refresh(stack, note)).orElse(false);
	}

	/** 自动记下"这篇笔记在这里"，给 /palace where 用。记录失败不影响看笔记。 */
	private static void recordLocation(Level level, BlockPos pos, int slot, UUID id) {
		NoteLocations locations = PalaceServer.locations();
		if (locations == null) {
			return;
		}
		try {
			locations.record(id, new NoteLocations.Location(level.dimension().identifier().toString(),
					pos.getX(), pos.getY(), pos.getZ(), slot, System.currentTimeMillis()));
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("记录笔记位置失败", e);
		}
	}

	// 照抄原版 ChiseledBookShelfBlock.removeBook（它是 private 的，调不到）
	private static void takeBook(Level level, BlockPos pos, Player player, ChiseledBookShelfBlockEntity shelf, int slot) {
		ItemStack book = shelf.removeItem(slot, 1);
		SoundEvent sound = book.is(Items.ENCHANTED_BOOK)
				? SoundEvents.CHISELED_BOOKSHELF_PICKUP_ENCHANTED : SoundEvents.CHISELED_BOOKSHELF_PICKUP;
		level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
		if (!player.getInventory().add(book)) {
			player.drop(book, false, Prediction.SERVER_ONLY);
		}
		level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
	}

	public static void openNote(ServerPlayer player, UUID id) {
		if (sendNote(player, id, false)) {
			NoteWatcher.watch(player, id);
		}
	}

	/**
	 * 读笔记并发给玩家。refresh = true 是热重载推送：出错时不刷屏提示。
	 * @return 是否发送成功
	 */
	static boolean sendNote(ServerPlayer player, UUID id, boolean refresh) {
		Vault vault = PalaceServer.vault();
		if (vault == null) {
			if (!refresh) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.no_vault"));
			}
			return false;
		}
		try {
			// 单人游戏里读文件在服务端线程上，太大的文件会卡顿，先挡掉（多人版再改成后台读取）
			Optional<Path> path = vault.pathOf(id);
			if (path.isPresent() && Files.exists(path.get()) && Files.size(path.get()) > MAX_FILE_BYTES) {
				if (!refresh) {
					player.sendSystemMessage(Component.translatable("message.memorypalace.note_too_large", MAX_FILE_BYTES / 1024));
				}
				return false;
			}
			Optional<String> body = vault.readBody(id);
			Optional<NoteSummary> note = vault.find(id);
			if (body.isEmpty() || note.isEmpty()) {
				if (!refresh) {
					player.sendSystemMessage(Component.translatable("message.memorypalace.note_missing"));
				}
				return false;
			}
			// 文件开头标着"已加密"，正文却不是密文（比如在外部编辑器里被改成了明文）：
			// 不能当普通笔记打开，否则会绕过加密的所有保护
			if (note.get().encrypted() != NoteCipher.isEncrypted(body.get())) {
				if (!refresh) {
					player.sendSystemMessage(Component.translatable("message.memorypalace.encryption_mismatch"));
				}
				return false;
			}
			// 分块发送，客户端收齐再拼，正文一个字都不截
			for (OpenNotePayload part : OpenNotePayload.split(id, note.get().title(), body.get(), refresh)) {
				ServerPlayNetworking.send(player, part);
			}
			return true;
		} catch (IOException e) {
			MemoryPalace.LOGGER.error("读取笔记 {} 失败", id, e);
			if (!refresh) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.read_failed"));
			}
			return false;
		}
	}
}
