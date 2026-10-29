package com.memorypalace.core.vault;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端处理一条随手记：限速、篇数上限、写进收件箱，并且每条都给出一个对应的保存结果（客户端按编号对上）。
 * 重发去重：
 * - 同一个编号已经保存成功过（记在内存里，每人最近 REMEMBER 条）→ 直接回"已保存"，不再追加
 * - 客户端标了"重发"：收件箱里已经有完全一样的这一行（服务器重启过、内存里没记录）→ 同样不再追加
 * 权限检查、笔记库是否打开由调用方（ToolPayloads）先做。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class CaptureService {
	/** 两次快速收集至少间隔这么久：人手按 K → 打字 → 回车远不会这么快，只挡住刷包。 */
	public static final long INTERVAL_MS = 200;
	/** 每人记住最近这么多条保存成功的编号。 */
	public static final int REMEMBER = 64;

	public enum Status { OK, BUSY, QUOTA, NOT_YOURS, ENCRYPTED, FULL, INVALID, FAILED }

	/** 保存结果。OK 时 detail 是收件箱的路径，QUOTA 时是篇数上限；FAILED 时 error 是原因（记日志用）。 */
	public record Result(Status status, String detail, IOException error) {
		static Result of(Status status) {
			return new Result(status, "", null);
		}
	}

	/** 往收件箱里写一条：明文（Inbox.append）或客户端加密好的（Inbox.appendSealed）。 */
	@FunctionalInterface
	public interface Write {
		NoteSummary write(Vault vault, UUID owner, OffsetDateTime time, boolean retry) throws IOException;
	}

	private final Map<UUID, Long> lastCapture = new HashMap<>();
	private final Map<UUID, Map<UUID, String>> saved = new HashMap<>();

	/**
	 * @param player   发送者
	 * @param owner    收件箱的主人（null = 本机房主）
	 * @param maxNotes 普通玩家的篇数上限（不限制时传 0，管理员也传 0）
	 * @param nowMs    单调递增的毫秒时间（限速用）
	 */
	public synchronized Result capture(Vault vault, UUID player, UUID owner, UUID requestId, boolean retry,
			OffsetDateTime time, int maxNotes, long nowMs, Write write) {
		Map<UUID, String> mine = saved.computeIfAbsent(player, k -> new LinkedHashMap<>(16, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
				return size() > REMEMBER;
			}
		});
		String done = mine.get(requestId);
		if (done != null) {
			return new Result(Status.OK, done, null);
		}
		Long last = lastCapture.put(player, nowMs);
		if (last != null && nowMs - last < INTERVAL_MS) {
			return Result.of(Status.BUSY);
		}
		// 当天的收件箱还没建：新建一篇算进普通玩家的篇数上限
		if (owner != null && maxNotes > 0 && vault.resolve(Inbox.pathFor(owner, time)).isEmpty()
				&& vault.list().stream().filter(n -> owner.equals(n.access().owner())).count() >= maxNotes) {
			return new Result(Status.QUOTA, Integer.toString(maxNotes), null);
		}
		try {
			NoteSummary inbox = write.write(vault, owner, time, retry);
			mine.put(requestId, inbox.relativePath());
			return new Result(Status.OK, inbox.relativePath(), null);
		} catch (IllegalArgumentException e) {
			return Result.of(Status.INVALID); // 空内容、不是合法的收件箱密文
		} catch (Inbox.NotYoursException e) {
			return Result.of(Status.NOT_YOURS);
		} catch (IllegalStateException e) {
			return Result.of(Status.ENCRYPTED);
		} catch (Inbox.FullException e) {
			return Result.of(Status.FULL);
		} catch (IOException e) {
			return new Result(Status.FAILED, "", e);
		}
	}
}
