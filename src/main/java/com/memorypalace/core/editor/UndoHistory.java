package com.memorypalace.core.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 编辑器的撤销 / 重做历史。只管文字和光标，不认识任何界面类，可以单独测试。
 * <p>
 * 每次内容变化调用 {@link #changed}：在同一处连续打字（每次只增删一个字、紧挨着上一次改动、间隔不到 {@link #MERGE_MS} 毫秒）
 * 合成一步；跳到别处再改、粘贴、剪切、删掉一大段，都单独算一步。编辑器在移动光标、改选区时再调用 {@link #breakMerge}，
 * 这样光标挪走又挪回来接着打字也会分开。撤销后再输入新内容，重做记录清空。
 * 历史总字数超过上限时丢掉最早的几步，免得长笔记撤销几百次把内存吃光。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class UndoHistory {
	/** 连续打字间隔不到这么久就合成一步。 */
	public static final long MERGE_MS = 1000;
	/** 最多记这么多步。 */
	public static final int MAX_STEPS = 200;
	/** 历史里所有版本合计最多这么多字。 */
	public static final long MAX_TOTAL_CHARS = 4_000_000;

	/** 一个版本：全文和光标位置。 */
	public record State(String text, int cursor) {}

	private final Deque<State> past = new ArrayDeque<>();
	private final Deque<State> future = new ArrayDeque<>();
	private State current;
	private long lastChange = Long.MIN_VALUE;
	private boolean lastWasSmall;
	// 上一次修改结束的位置（改完后光标大概在这里）
	private int lastCursor;
	private long totalChars;

	public UndoHistory(String initialText) {
		current = new State(initialText, initialText.length());
		lastCursor = initialText.length();
	}

	/** 内容变成了 text（用户的修改，不是撤销/重做本身造成的）。now 是当前时间（毫秒）。 */
	public void changed(String text, long now) {
		if (text.equals(current.text())) {
			return;
		}
		Diff diff = Diff.of(current.text(), text);
		// 只插入或只删除了一个字（打字、退格）；把一个字换成另一个字也算
		boolean small = diff.removed() <= 1 && diff.inserted() <= 1;
		State next = new State(text, diff.cursorAfter());
		// 还要紧挨着上一次改动：在上次停下的地方继续打字（改动从这里开始）、退格（改动到这里结束）、
		// 向后删除（改动从这里开始）。跳到别处再改，哪怕只隔了 100 毫秒也单独算一步
		boolean adjacent = diff.prefix() == lastCursor || diff.prefix() + diff.removed() == lastCursor;
		boolean merge = small && lastWasSmall && adjacent && now - lastChange < MERGE_MS && now >= lastChange;
		if (!merge) {
			push(past, current);
			trim();
		}
		clear(future);
		current = next;
		lastChange = now;
		lastWasSmall = small;
		lastCursor = diff.cursorAfter();
	}

	public boolean canUndo() {
		return !past.isEmpty();
	}

	public boolean canRedo() {
		return !future.isEmpty();
	}

	/** 撤销一步，返回要恢复成的版本；没得撤销时返回 null。 */
	public State undo() {
		if (past.isEmpty()) {
			return null;
		}
		push(future, current);
		current = pop(past);
		breakMerge();
		return current;
	}

	/** 重做一步，返回要恢复成的版本；没得重做时返回 null。 */
	public State redo() {
		if (future.isEmpty()) {
			return null;
		}
		push(past, current);
		current = pop(future);
		breakMerge();
		return current;
	}

	/** 下一次修改一定单独算一步（比如刚保存过、刚撤销过）。 */
	public void breakMerge() {
		lastWasSmall = false;
	}

	/**
	 * 新旧文字的差别：开头 prefix 个字、结尾 suffix 个字没变，中间删了 removed 个、插入了 inserted 个。
	 * cursorAfter = 改动的末尾，修改之后光标大概就在这里。
	 */
	record Diff(int prefix, int suffix, int removed, int inserted, int cursorAfter) {
		static Diff of(String before, String after) {
			int max = Math.min(before.length(), after.length());
			int prefix = 0;
			while (prefix < max && before.charAt(prefix) == after.charAt(prefix)) {
				prefix++;
			}
			int suffix = 0;
			while (suffix < max - prefix
					&& before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
				suffix++;
			}
			return new Diff(prefix, suffix, before.length() - prefix - suffix, after.length() - prefix - suffix,
					after.length() - suffix);
		}
	}

	private void push(Deque<State> stack, State state) {
		stack.push(state);
		totalChars += state.text().length();
	}

	private State pop(Deque<State> stack) {
		State state = stack.pop();
		totalChars -= state.text().length();
		return state;
	}

	private void clear(Deque<State> stack) {
		while (!stack.isEmpty()) {
			pop(stack);
		}
	}

	// 超过步数或总字数上限：从最早的开始丢（至少留一步，保证刚做的这次能撤销）
	private void trim() {
		while (past.size() > 1 && (past.size() > MAX_STEPS || totalChars > MAX_TOTAL_CHARS)) {
			totalChars -= past.removeLast().text().length();
		}
	}
}
