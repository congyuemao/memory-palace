package com.memorypalace.core.editor;

import com.memorypalace.core.editor.UndoHistory.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UndoHistoryTest {
	@Test
	void freshHistoryHasNothingToUndoOrRedo() {
		var history = new UndoHistory("initial");

		assertFalse(history.canUndo());
		assertFalse(history.canRedo());
		assertNull(history.undo());
		assertNull(history.redo());
	}

	@Test
	void consecutiveTypingMergesIntoOneStep() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("ab", 100);
		history.changed("abc", 200);

		assertTrue(history.canUndo());
		assertEquals(new State("", 0), history.undo());
		assertFalse(history.canUndo());
		assertNull(history.undo());
		assertEquals(new State("abc", 3), history.redo());
	}

	@Test
	void mergeWindowIsMeasuredFromTheMostRecentEdit() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("ab", UndoHistory.MERGE_MS - 1);
		history.changed("abc", 2 * (UndoHistory.MERGE_MS - 1));

		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void pausesAtOrAboveMergeWindowStartNewSteps() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("ab", UndoHistory.MERGE_MS);
		history.changed("abc", 3 * UndoHistory.MERGE_MS);

		assertEquals(new State("ab", 2), history.undo());
		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void pasteIsItsOwnStep() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("apasted", 100);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void smallEditsAfterPasteDoNotMergeIntoThePaste() {
		var history = new UndoHistory("");
		history.changed("pasted", 0);
		history.changed("pasted!", 100);
		history.changed("pasted!?", 200);

		assertEquals(new State("pasted", 6), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void deletingASelectionIsSeparateFromSurroundingSmallEdits() {
		var history = new UndoHistory("abcdef");
		history.changed("abcdefg", 0);
		history.changed("abfg", 100);
		history.changed("abfg!", 200);

		assertEquals(new State("abfg", 2), history.undo());
		assertEquals(new State("abcdefg", 7), history.undo());
		assertEquals(new State("abcdef", 6), history.undo());
		assertNull(history.undo());
	}

	@Test
	void singleCharacterReplacementsMerge() {
		var history = new UndoHistory("cat");
		history.changed("bat", 0);
		history.changed("bit", 100);
		history.changed("big", 200);

		assertEquals(new State("cat", 3), history.undo());
		assertNull(history.undo());
		assertEquals(new State("big", 3), history.redo());
	}

	@Test
	void editAtAnotherPositionIsItsOwnStepEvenWithinTheMergeWindow() {
		// 回归：在末尾打 d，100 毫秒后跳到开头打 X，撤销一次应只撤掉 X、留下 abcd
		var history = new UndoHistory("abc");
		history.changed("abcd", 0);
		history.changed("Xabcd", 100);

		assertEquals(new State("abcd", 4), history.undo(), "两处不相连的修改不能绑成一步");
		assertEquals(new State("abc", 3), history.undo());
		assertNull(history.undo());
	}

	@Test
	void typingContinuesToMergeWhenEachCharFollowsThePrevious() {
		var history = new UndoHistory("abc");
		history.changed("Xabc", 0);
		history.changed("XYabc", 100);
		history.changed("XYZabc", 200);

		assertEquals(new State("abc", 3), history.undo(), "在同一处连续打字仍然合成一步");
		assertNull(history.undo());
	}

	@Test
	void consecutiveBackspacesMerge() {
		var history = new UndoHistory("abcde");
		history.changed("abcd", 0);
		history.changed("abc", 100);
		history.changed("ab", 200);

		assertEquals(new State("abcde", 5), history.undo(), "连续退格合成一步");
		assertNull(history.undo());
	}

	@Test
	void consecutiveForwardDeletesMerge() {
		// 光标停在 a 后面连按 Delete：每次删掉光标后的一个字
		var history = new UndoHistory("abcde");
		history.changed("acde", 0);
		history.changed("ade", 100);
		history.changed("ae", 200);

		assertEquals(new State("abcde", 5), history.undo(), "连续向后删除合成一步");
		assertNull(history.undo());
	}

	@Test
	void typingThenDeletingAtAnotherPlaceIsTwoSteps() {
		var history = new UndoHistory("abc");
		history.changed("abcd", 0);
		history.changed("bcd", 100);

		assertEquals(new State("abcd", 4), history.undo());
		assertEquals(new State("abc", 3), history.undo());
	}

	@Test
	void identicalTextDoesNotAddAStep() {
		var history = new UndoHistory("a");
		history.changed(new String("a"), 0);
		assertFalse(history.canUndo());
		assertNull(history.undo());

		history.changed("ab", 100);
		history.changed(new String("ab"), 100 + UndoHistory.MERGE_MS);
		assertEquals(new State("a", 1), history.undo());
		assertNull(history.undo());
	}

	@Test
	void identicalTextKeepsTheRedoStack() {
		var history = new UndoHistory("ac");
		history.changed("abc", 0);
		assertEquals(new State("ac", 2), history.undo());

		history.changed(new String("ac"), 100);

		assertFalse(history.canUndo());
		assertTrue(history.canRedo());
		assertEquals(new State("abc", 2), history.redo());
		assertFalse(history.canRedo());
	}

	@Test
	void identicalTextDoesNotExtendTheMergeWindow() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("a", UndoHistory.MERGE_MS - 1);
		history.changed("ab", UndoHistory.MERGE_MS);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void undoThenRedoRestoresTextAndCursor() {
		var history = new UndoHistory("ac");
		history.changed("abc", 0);

		assertEquals(new State("ac", 2), history.undo());
		assertTrue(history.canRedo());
		var restored = history.redo();
		assertEquals(new State("abc", 2), restored);
		assertTrue(history.canUndo());
		assertFalse(history.canRedo());
		assertEquals(new State("ac", 2), history.undo());
		assertEquals(restored, history.redo());
	}

	@Test
	void multipleUndosAndRedosFollowTheStateOrder() {
		var history = new UndoHistory("ab");
		history.changed("aXb", 0);
		history.changed("aXYb", UndoHistory.MERGE_MS);
		history.changed("aYb", 2 * UndoHistory.MERGE_MS);

		assertEquals(new State("aXYb", 3), history.undo());
		assertEquals(new State("aXb", 2), history.undo());
		assertEquals(new State("ab", 2), history.undo());
		assertFalse(history.canUndo());
		assertNull(history.undo());

		assertEquals(new State("aXb", 2), history.redo());
		assertEquals(new State("aXYb", 3), history.redo());
		assertEquals(new State("aYb", 1), history.redo());
		assertFalse(history.canRedo());
		assertNull(history.redo());
	}

	@Test
	void newChangeAfterUndoClearsAllRedoSteps() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("ab", UndoHistory.MERGE_MS);
		history.changed("abc", 2 * UndoHistory.MERGE_MS);
		assertEquals(new State("ab", 2), history.undo());
		assertEquals(new State("a", 1), history.undo());
		assertTrue(history.canRedo());

		history.changed("ax", 2 * UndoHistory.MERGE_MS + 100);

		assertFalse(history.canRedo());
		assertNull(history.redo());
	}

	@Test
	void smallEditAfterUndoDoesNotMergeWithThePreviousStep() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("ab", UndoHistory.MERGE_MS);
		assertEquals(new State("a", 1), history.undo());

		history.changed("ax", UndoHistory.MERGE_MS + 100);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void breakMergeStartsANewTypingGroup() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.breakMerge();
		history.changed("ab", 100);
		history.changed("abc", 200);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void smallEditAfterRedoDoesNotMergeWithTheRestoredStep() {
		var history = new UndoHistory("");
		history.changed("a", 0);
		assertEquals(new State("", 0), history.undo());
		assertEquals(new State("a", 1), history.redo());

		history.changed("ab", 100);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void initialCursorIsAtTheEndOfTheInitialText() {
		var history = new UndoHistory("initial");
		history.changed("Xinitial", 0);

		assertEquals(new State("initial", 7), history.undo());
	}

	@Test
	void insertionCursorIsAtTheEndOfTheInsertedText() {
		var history = new UndoHistory("abcd");
		history.changed("abXYZcd", 0);

		assertEquals(new State("abcd", 4), history.undo());
		assertEquals(new State("abXYZcd", 5), history.redo());
	}

	@Test
	void deletionCursorIsWhereTheDeletionHappened() {
		var history = new UndoHistory("abXYZcd");
		history.changed("abcd", 0);

		assertEquals(new State("abXYZcd", 7), history.undo());
		assertEquals(new State("abcd", 2), history.redo());
	}

	@Test
	void backwardsTimestampDoesNotMerge() {
		var history = new UndoHistory("");
		history.changed("a", 100);
		history.changed("ab", 50);

		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}

	@Test
	void stepLimitDropsTheOldestStepsAndKeepsTheNewest() {
		var history = new UndoHistory("");
		int steps = UndoHistory.MAX_STEPS + 5;
		for (int i = 1; i <= steps; i++) {
			history.changed("x".repeat(i), i * UndoHistory.MERGE_MS);
		}

		for (int i = 1; i <= UndoHistory.MAX_STEPS; i++) {
			int length = steps - i;
			assertTrue(history.canUndo());
			assertEquals(new State("x".repeat(length), length), history.undo());
		}
		assertFalse(history.canUndo());
		assertNull(history.undo());
	}

	@Test
	void characterBudgetLimitsLongTextHistoryAndKeepsTheNewestStep() {
		int textLength = 1_000_000;
		int steps = 10;
		var history = new UndoHistory("x".repeat(textLength));
		for (int i = 1; i <= steps; i++) {
			history.changed("x".repeat(textLength + i), i * UndoHistory.MERGE_MS);
		}

		int undoCount = 0;
		long retainedChars = 0;
		State state;
		while ((state = history.undo()) != null) {
			undoCount++;
			assertEquals(textLength + steps - undoCount, state.text().length(), "保留的历史从最近一步开始");
			assertEquals(state.text().length(), state.cursor());
			retainedChars += state.text().length();
		}
		assertTrue(undoCount >= 1, "至少保留最近一步");
		assertTrue(undoCount < steps, "长文本应触发字数上限");
		assertTrue(undoCount <= UndoHistory.MAX_TOTAL_CHARS / textLength);
		assertTrue(retainedChars <= UndoHistory.MAX_TOTAL_CHARS, "保留的历史总字数不能超过上限");
		assertFalse(history.canUndo());
		assertNull(history.undo());
	}

	@Test
	void newestStepSurvivesEvenWhenOneStateExceedsTheCharacterBudget() {
		String initial = "x".repeat(Math.toIntExact(UndoHistory.MAX_TOTAL_CHARS) + 1);
		var history = new UndoHistory(initial);
		history.changed(initial + "a", 0);
		history.changed(initial + "ab", UndoHistory.MERGE_MS);

		assertTrue(history.canUndo());
		var state = history.undo();
		assertNotNull(state);
		assertTrue((initial + "a").equals(state.text()), "超出字数上限也要能撤销最近一步");
		assertEquals(initial.length() + 1, state.cursor());
		assertFalse(history.canUndo());
		assertNull(history.undo());
	}

	@Test
	void insertingASurrogatePairIsItsOwnStep() {
		String emoji = "\uD83D\uDE00";
		var history = new UndoHistory("");
		history.changed("a", 0);
		history.changed("a" + emoji, 100);
		history.changed("a" + emoji + "b", 200);

		assertEquals(new State("a" + emoji, 3), history.undo());
		assertEquals(new State("a", 1), history.undo());
		assertEquals(new State("", 0), history.undo());
		assertNull(history.undo());
	}
}
