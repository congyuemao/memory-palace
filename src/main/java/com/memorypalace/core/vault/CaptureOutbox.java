package com.memorypalace.core.vault;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 客户端的随手记待发队列：一条随手记从按回车起，到收到服务端对应的保存结果之前都留在这里。
 * 读钥匙圈失败、加密失败、服务端拒写、等不到结果、断开连接，这条都变成"没保存"，按 K 时恢复原文让玩家重试；
 * 只有收到"已保存"才移除。只在内存里，不落盘（加密模式的原文也不会以明文写进硬盘）。
 *
 * 重试去重：原文没改、还在同一个世界、上次准备好的内容还能用，就沿用同一个编号、同一份内容（同一个时刻、
 * 同一行密文）重发，服务端据此认出是同一条（见 CaptureService）。改了原文、换了世界，就当作新的一条。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。只在一个线程（渲染线程）使用。
 */
public final class CaptureOutbox {
	/** 发出（或开始读钥匙圈）之后这么久还没有结果，就当作没保存，让玩家找回重试。 */
	public static final long TIMEOUT_MS = 15_000;

	public enum State {
		/** 正在读钥匙圈、加密。 */
		PREPARING,
		/** 公钥对不上，等玩家在确认框里选（不计时）。 */
		CONFIRMING,
		/** 已发给服务端，等结果。 */
		SENT,
		/** 没保存（或不知道保存了没有），等玩家找回重试。 */
		FAILED,
		/** 服务端确认已保存。 */
		SAVED
	}

	public static final class Draft {
		private final UUID id = UUID.randomUUID();
		private final String text;
		private final OffsetDateTime time;
		private final String where;
		private State state = State.PREPARING;
		private int attempt;
		private boolean prepared;
		private String sealedLine;
		private int sends;
		private long deadline;
		private String edited;

		private Draft(String text, OffsetDateTime time, String where) {
			this.text = text;
			this.time = time;
			this.where = where;
		}

		/** 这条随手记的编号（服务端回结果时带着它）。 */
		public UUID id() {
			return id;
		}

		public String text() {
			return text;
		}

		/** 第一次按回车时的本地时间：重发也用它，记进同一天、同一分钟。 */
		public OffsetDateTime time() {
			return time;
		}

		public State state() {
			return state;
		}

		/** 第几次尝试：每次重试 +1。异步回来的钥匙圈、确认框结果要核对它，迟到的旧结果作废。 */
		public int attempt() {
			return attempt;
		}

		/** 已经决定好发什么（明文，或者 sealedLine）。 */
		public boolean prepared() {
			return prepared;
		}

		/** 加密好的一行；null = 发明文。 */
		public String sealedLine() {
			return sealedLine;
		}

		/** 找回时输入框里显示什么：上次在找回界面改了一半没发的，就显示改过的。 */
		public String restoreText() {
			return edited != null ? edited : text;
		}
	}

	private final List<Draft> drafts = new ArrayList<>();

	/**
	 * 发送 text。from = 输入框是从哪条没保存的随手记找回来的（null = 新写的）。
	 * - from 原文没改、同一个世界（where）、准备好的内容还能用 → 沿用 from（同一个编号）。"还能用"：加密过的一行；
	 *   或者明文而且现在没要求加密。要求加密时绝不拿上次的明文重发（上次没钥匙圈、现在可能有了），重新准备。
	 * - from 已经确认保存过、原文没改 → 返回 null，不用再发
	 * - 否则新开一条（from 被它取代，从队列里去掉）
	 * 返回的这条处于 PREPARING：prepared() 为真就直接 sending，否则先准备（读钥匙圈、加密）。
	 */
	public Draft begin(Draft from, String text, OffsetDateTime now, String where, boolean encrypt, long nowMs) {
		if (from != null && from.text.equals(text)) {
			if (from.state == State.SAVED) {
				return null;
			}
			boolean reusable = !from.prepared || from.sealedLine != null || !encrypt;
			if (drafts.contains(from) && Objects.equals(from.where, where) && reusable) {
				from.state = State.PREPARING;
				from.attempt++;
				from.edited = null;
				from.deadline = nowMs + TIMEOUT_MS;
				return from;
			}
		}
		if (from != null) {
			drafts.remove(from);
		}
		Draft draft = new Draft(text, now, where);
		draft.deadline = nowMs + TIMEOUT_MS;
		drafts.add(draft);
		return draft;
	}

	/** 准备好了：sealedLine 为 null 表示发明文。 */
	public void prepare(Draft draft, String sealedLine) {
		draft.prepared = true;
		draft.sealedLine = sealedLine;
	}

	/** 马上要发给服务端：开始计时。返回这次是不是重发（同一个编号之前发过）。 */
	public boolean sending(Draft draft, long nowMs) {
		if (!draft.prepared) {
			throw new IllegalStateException("还没准备好");
		}
		boolean retry = draft.sends > 0;
		draft.sends++;
		draft.state = State.SENT;
		draft.deadline = nowMs + TIMEOUT_MS;
		return retry;
	}

	/** 弹出公钥确认框：等玩家选，不计时。 */
	public void confirming(Draft draft) {
		draft.state = State.CONFIRMING;
		draft.deadline = 0;
	}

	/** 这条没保存成功（或者玩家取消了），留在队列里等找回。已经保存过的不受影响。 */
	public void fail(Draft draft) {
		if (draft.state != State.SAVED) {
			draft.state = State.FAILED;
			draft.deadline = 0;
		}
	}

	/** 服务端的结果对上了哪一条。late = 之前已经当作没保存（超时、断线）了，现在才收到。 */
	public record Receipt(Draft draft, boolean late) {}

	/** 收到服务端的结果；编号对不上任何一条时为 null（比如已经放弃了的）。 */
	public Receipt onResult(UUID id, boolean ok) {
		for (Draft draft : drafts) {
			if (draft.id.equals(id)) {
				boolean late = draft.state == State.FAILED;
				if (ok) {
					draft.state = State.SAVED;
					draft.deadline = 0;
					drafts.remove(draft);
				} else {
					fail(draft);
				}
				return new Receipt(draft, late);
			}
		}
		return null;
	}

	/** 到时间还没结果的：当作没保存，返回这些（用来提示玩家）。 */
	public List<Draft> expire(long nowMs) {
		List<Draft> out = new ArrayList<>();
		for (Draft draft : drafts) {
			if (draft.deadline != 0 && nowMs - draft.deadline >= 0) {
				fail(draft);
				out.add(draft);
			}
		}
		return out;
	}

	/** 离开世界：还没结果的都当作没保存（编号和内容留着，回到同一个世界重试时服务端还能认出来）。 */
	public void disconnect() {
		drafts.forEach(this::fail);
	}

	/**
	 * 在 where 这个世界里最早一条没保存的；没有就是 null。
	 * 只找回本世界的：别的世界（别的服务器）记的随手记不拿出来，免得一回车发给了另一个服主看得见的收件箱。
	 */
	public Draft nextFailed(String where) {
		for (Draft draft : drafts) {
			if (draft.state == State.FAILED && Objects.equals(draft.where, where)) {
				return draft;
			}
		}
		return null;
	}

	/** where 这个世界里有几条没保存、等着找回。 */
	public int failedCount(String where) {
		return (int) drafts.stream().filter(d -> d.state == State.FAILED && Objects.equals(d.where, where)).count();
	}

	/** 找回界面里改了但没发（按了 Esc）：下次找回时显示改过的。 */
	public void keepEdit(Draft draft, String text) {
		draft.edited = text.equals(draft.text) ? null : text;
	}

	/** 玩家清空内容放弃这条。 */
	public void discard(Draft draft) {
		drafts.remove(draft);
	}

	/** 队列里还有几条（测试用）。 */
	public int size() {
		return drafts.size();
	}
}
