package com.memorypalace.client.crypto;

import com.memorypalace.core.batch.PacedBatch;
import com.memorypalace.mc.EditPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 整个文件夹逐篇加密（/palace lockfolder）或逐篇解密（/palace unlockfolder）时的保存队列。
 * 服务端对保存限速（每人每秒 1 次、最多连着 10 次，房主也一样），所以不能一口气全发：
 * 一次只发一篇，等结果回来再发下一篇；服务端说 BUSY 就等一会儿重试同一篇（见 PacedBatch）。
 * 冲突（文件在外部被改过）、没权限、出错不重试，算失败：不覆盖已经变了的内容。
 * 服务端发完（"…_done"）、队列也空了，就报告"成功几篇、失败几篇、未处理几篇"；
 * 解除文件夹密码时，只有全部成功才删锁。同一时间只有一批。只在渲染线程使用。
 */
public final class FolderBatch {
	public enum Kind { ENCRYPT, DECRYPT }

	/** 一篇要保存的：stored = 要写进去的正文（加密时是密文，解密时是明文），guardHash = 服务端那边原来正文的指纹。 */
	public record SaveJob(UUID noteId, String stored, String guardHash, Consumer<Boolean> after) {}

	// 服务端限速每秒补 1 次：BUSY 后等 1.1 秒再试；一篇最多重试 30 次
	private static final long RETRY_DELAY_MS = 1100;
	private static final int MAX_RETRIES = 30;
	// 一批最多做这么久（笔记多、网络慢也够了），过时的服务端消息不再认
	private static final long MAX_NANOS = 30L * 60 * 1_000_000_000L;

	private static Kind kind;
	private static String folder;
	private static String lock;     // 解除密码时：开始时的锁内容（删锁时核对）
	private static String lockId;
	private static PacedBatch<SaveJob> batch;
	private static long startedAt;
	private static boolean serverDone;
	private static int serverSent;
	private static int held;        // 还在等别的事（比如等玩家输保险库密码）的篇数：没处理完不能报告

	private FolderBatch() {}

	/** 开始新的一批（之前没做完的那批直接作废）。 */
	public static void start(Kind newKind, String newFolder, String newLockId, String lockContent) {
		kind = newKind;
		folder = newFolder;
		lockId = newLockId;
		lock = lockContent;
		batch = new PacedBatch<>(RETRY_DELAY_MS, MAX_RETRIES);
		startedAt = System.nanoTime();
		serverDone = false;
		serverSent = 0;
		held = 0;
	}

	/** 正在做的这一批是不是这种、这把锁（服务端发来的单篇要对得上才认）。 */
	public static boolean active(Kind expected, String expectedLockId) {
		return batch != null && kind == expected && lockId != null && lockId.equals(expectedLockId)
				&& System.nanoTime() - startedAt <= MAX_NANOS;
	}

	public static String lockId() {
		return batch == null ? null : lockId;
	}

	public static void add(SaveJob job) {
		if (batch != null) {
			batch.add(job);
		}
	}

	/** 这一篇没法处理（解不开等），直接算失败。 */
	public static void failed() {
		if (batch != null) {
			batch.skipFailed();
			finishIfDone();
		}
	}

	/** 有一篇先放一边（等玩家输保险库密码），处理完之前不报告。 */
	public static void hold() {
		held++;
	}

	public static void release() {
		held = Math.max(0, held - 1);
		finishIfDone();
	}

	/** 服务端说发完了（sent = 它发了几篇）。 */
	public static void serverDone(Kind doneKind, String doneFolder, int sent) {
		if (batch != null && kind == doneKind && folder.equals(doneFolder)) {
			serverDone = true;
			serverSent = sent;
			finishIfDone();
		}
	}

	/** 每个客户端刻调用：可以发下一篇就发。 */
	public static void tick() {
		PacedBatch<SaveJob> current = batch;
		if (current == null) {
			return;
		}
		current.next(System.currentTimeMillis()).ifPresent(job -> {
			Consumer<EditPayloads.SaveResult> onResult = result -> {
				if (batch != current) {
					return; // 这一批已经作废了（又开始了新的一批、离开了世界）
				}
				PacedBatch.Outcome outcome = switch (result.result()) {
					case "OK" -> PacedBatch.Outcome.OK;
					case "BUSY" -> PacedBatch.Outcome.BUSY;
					default -> PacedBatch.Outcome.FAILED;
				};
				current.result(outcome, System.currentTimeMillis());
				if (outcome != PacedBatch.Outcome.BUSY) {
					job.after().accept(outcome == PacedBatch.Outcome.OK);
				}
				finishIfDone();
			};
			if (kind == Kind.DECRYPT) {
				NoteSaver.saveDecrypted(job.noteId(), job.stored(), job.guardHash(), onResult);
			} else {
				NoteSaver.save(job.noteId(), job.stored(), job.guardHash(), onResult);
			}
		});
	}

	/** 上锁了：排着队还没发的取消（解密的那些是明文，不能留在内存里），按"未处理"报告。 */
	public static void onLock() {
		if (batch == null) {
			return;
		}
		batch.cancelQueued().forEach(job -> job.after().accept(false));
		held = 0;
		serverDone = true;
		finishIfDone();
	}

	/** 离开世界：直接丢掉。 */
	public static void reset() {
		batch = null;
	}

	private static void finishIfDone() {
		PacedBatch<SaveJob> current = batch;
		if (current == null || !serverDone || held > 0 || !current.idle()) {
			return;
		}
		batch = null;
		PacedBatch.Counts counts = current.counts();
		// 服务端发了几篇是总数：客户端没认的（对不上锁之类）也算未处理
		int unprocessed = Math.max(counts.unprocessed(), serverSent - counts.done() - counts.failed());
		boolean complete = counts.failed() == 0 && unprocessed == 0;
		if (kind == Kind.ENCRYPT) {
			tell(Component.translatable("message.memorypalace.folder_encrypt_summary", folder, counts.done(), counts.failed(), unprocessed));
			if (!complete) {
				tell(Component.translatable("message.memorypalace.folder_encrypt_resume", folder));
			}
			return;
		}
		if (!complete) {
			tell(Component.translatable("message.memorypalace.folder_unlock_summary", folder, counts.done(), counts.failed(), unprocessed));
			return;
		}
		String doneFolder = folder;
		FolderKeys.deleteLock(doneFolder, lock, ok -> {
			if (ok) {
				tell(Component.translatable("message.memorypalace.folder_unlocked_count", doneFolder, counts.done()));
			}
		});
	}

	private static void tell(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}
}
