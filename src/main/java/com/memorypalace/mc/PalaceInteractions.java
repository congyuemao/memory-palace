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
import com.memorypalace.core.crypto.Ciphertext;
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
	static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

	/** 笔记文件超过 MAX_FILE_BYTES（读之前先看大小，不让大文件被反复整篇读进来）。查不了大小也当太大。 */
	static boolean tooLarge(Vault vault, UUID id) {
		try {
			Optional<Path> path = vault.pathOf(id);
			return path.isPresent() && Files.exists(path.get()) && Files.size(path.get()) > MAX_FILE_BYTES;
		} catch (IOException e) {
			return true;
		}
	}

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
			if (level.isClientSide()) {
				markLocalOpen(id.get());
			}
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
				// 客户端不知道讲台上是哪本书（原版不同步），只能确认上面确实放着书
				if (level.isClientSide() && !player.isSecondaryUseActive() && state.getValue(LecternBlock.HAS_BOOK)) {
					markLocalOpen(null);
				}
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
				// 只有真打开了才记位置：读不了的人狂点书架，不该让服务端一遍遍重写位置文件
				if (openNote(serverPlayer, id.get())) {
					recordLocation(level, hit.getBlockPos(), NoteLocations.LECTERN_SLOT, id.get());
				}
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
			// 客户端看不到书架里是哪本书（原版不同步），只能确认点中的格子里有书
			if (level.isClientSide()) {
				OptionalInt clicked = shelf.getHitSlot(hit, state.getValue(HorizontalDirectionalBlock.FACING));
				if (clicked.isPresent() && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(clicked.getAsInt()))) {
					markLocalOpen(null);
				}
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
			if (openNote(serverPlayer, id.get())) {
				recordLocation(level, hit.getBlockPos(), slot.getAsInt(), id.get());
			}
			return InteractionResult.SUCCESS;
		});
	}

	// 客户端这一侧：玩家上一次亲手右键笔记书 / 放着书的讲台 / 书架上有书的格子的时间，以及（手里拿着书时）是哪一篇。
	// 服务端发来"输阅读密码"时，客户端只在刚右键过（几秒内、用一次作废、手持的书要对得上）时才弹框，
	// 恶意服务端不能随时弹框骗密码
	private record LocalOpen(UUID noteId, long atNanos) {}

	private static volatile LocalOpen localOpen;

	private static void markLocalOpen(UUID noteId) {
		localOpen = new LocalOpen(noteId, System.nanoTime());
	}

	/** 客户端用：离开世界、进入世界时清掉（不能把上一个服务器里的右键带到下一个服务器）。 */
	public static void clearLocalOpen() {
		localOpen = null;
	}

	/**
	 * 客户端用：玩家是不是在 withinNanos 之内亲手右键过这篇的书（用一次就作废）。
	 * 讲台、书架上的书客户端不知道是哪篇，只能按"刚右键过一本书"算。
	 */
	public static boolean takeLocalOpen(UUID noteId, long withinNanos) {
		LocalOpen open = localOpen;
		localOpen = null;
		return open != null && System.nanoTime() - open.atNanos() <= withinNanos
				&& (open.noteId() == null || open.noteId().equals(noteId));
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

	// 打开笔记（右键、找笔记里点开）：每人每秒 2 次、最多连着 8 次。每次都要读文件、把整篇发回去，
	// 不限速的话一个几十字节的请求就能换来几百 KB 的读盘和发送
	private static final RateLimiter OPEN_RATE = new RateLimiter(2, 8);

	/** @return 打开成功（读得了、发出去了） */
	public static boolean openNote(ServerPlayer player, UUID id) {
		if (!PalaceAccess.isHost(player) && !OPEN_RATE.tryTake(player.getUUID())) {
			return false;
		}
		if (sendNote(player, id, false)) {
			NoteWatcher.watch(player, id);
			return true;
		}
		return false;
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
		if (!PalaceAccess.enabled(player)) {
			if (!refresh) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.multiplayer_off"));
			}
			return false;
		}
		// 先按索引里的卡片挡掉明显读不了的（每 2 秒按文件刷新一次），免得别人的笔记也要整篇读一遍才拒绝；
		// 卡片说读得了，下面 readNote 还会按文件此刻的内容再查一次
		if (vault.find(id).filter(n -> PalaceAccess.canRead(player, n)).isEmpty()) {
			// 设了阅读密码的加密笔记：让对方输密码（见 SharePayloads），不说"读不了"
			if (!refresh && !SharePayloads.offer(player, id)) {
				player.sendSystemMessage(Component.translatable("message.memorypalace.note_unavailable"));
			}
			return false;
		}
		try {
			// 单人游戏里读文件在服务端线程上，太大的文件会卡顿，先挡掉（多人版再改成后台读取）
			Optional<Path> path = vault.pathOf(id);
			if (path.isPresent() && Files.exists(path.get()) && Files.size(path.get()) > MAX_FILE_BYTES) {
				if (!refresh) {
					// 没权限的人不该知道"这篇很大"：说法和不存在一样
					boolean readable = vault.find(id).filter(n -> PalaceAccess.canRead(player, n)).isPresent();
					player.sendSystemMessage(readable
							? Component.translatable("message.memorypalace.note_too_large", MAX_FILE_BYTES / 1024)
							: Component.translatable("message.memorypalace.note_unavailable"));
				}
				return false;
			}
			// 读文件和取权限是同一次（readNote 会按文件此刻的内容刷新权限）：外部刚改过 palace-read 也立刻生效。
			// 热重载推送也走这里，所以被收回权限的人不会再收到新内容
			Optional<Vault.Read> read = vault.readNote(id);
			if (read.isEmpty() || !PalaceAccess.canRead(player, read.get().note())) {
				// "不存在"和"没权限"说法一样：不透露有没有这篇、是不是别人的收件箱或带锁笔记
				if (!refresh && (read.isEmpty() || !SharePayloads.offer(player, id))) {
					player.sendSystemMessage(Component.translatable("message.memorypalace.note_unavailable"));
				}
				return false;
			}
			Optional<NoteSummary> note = Optional.of(read.get().note());
			Optional<String> body = Optional.of(read.get().body());
			// 文件开头标着"已加密"，正文却不是密文（比如在外部编辑器里被改成了明文）：
			// 不能当普通笔记打开，否则会绕过加密的所有保护
			if (note.get().encrypted() != Ciphertext.isEncrypted(body.get())) {
				if (!refresh) {
					player.sendSystemMessage(Component.translatable("message.memorypalace.encryption_mismatch"));
				}
				return false;
			}
			// 分块发送，客户端收齐再拼，正文一个字都不截
			for (OpenNotePayload part : OpenNotePayload.split(id, note.get().title(), body.get(), refresh,
					PalaceAccess.canEdit(player, note.get()))) {
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
