package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NoteHistoryTest {
	private static final UUID NOTE = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

	@TempDir
	Path vault;

	/** 可以往前拨的时钟。 */
	private static final class MovableClock extends Clock {
		Instant now = Instant.parse("2026-10-28T08:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final MovableClock clock = new MovableClock();

	private NoteHistory history() {
		return new NoteHistory(vault, clock);
	}

	@Test
	void savesTheBodyBeforeEachEditingSessionAndReadsItBack() throws Exception {
		NoteHistory history = history();
		assertTrue(history.record(NOTE, "第一版", "第二版", false));
		List<NoteHistory.Version> versions = history.list(NOTE);
		assertEquals(1, versions.size());
		assertEquals("2026-10-28T08-00-00.000Z", versions.get(0).id());
		assertEquals(3, versions.get(0).chars());
		assertFalse(versions.get(0).encrypted());
		assertEquals("第一版", history.read(NOTE, versions.get(0).id()).orElseThrow());
		assertTrue(Files.isRegularFile(vault.resolve(".palace/history/" + NOTE + "/2026-10-28T08-00-00.000Z.md")));
		assertTrue(history.list(OTHER).isEmpty());
	}

	@Test
	void savesWithinTenMinutesAreMergedButRestoreAlwaysKeepsOne() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "开始前", "改了一点", false);
		clock.advance(Duration.ofMinutes(3));
		history.record(NOTE, "改了一点", "又改了一点", false);
		clock.advance(Duration.ofMinutes(6));
		history.record(NOTE, "又改了一点", "改完了", false);
		assertEquals(List.of("开始前"), bodies(history));

		clock.advance(Duration.ofMinutes(1)); // 距第一份正好 10 分钟：新的一段
		history.record(NOTE, "改完了", "第二天", false);
		assertEquals(List.of("改完了", "开始前"), bodies(history));

		clock.advance(Duration.ofSeconds(5));
		history.record(NOTE, "第二天", "还原成开始前", true);
		assertEquals(List.of("第二天", "改完了", "开始前"), bodies(history));
	}

	@Test
	void keepsOnlyTheNewestFiveAndSkipsDuplicatesAndNoOps() throws Exception {
		NoteHistory history = history();
		for (int i = 1; i <= 7; i++) {
			history.record(NOTE, "版本 " + i, "版本 " + (i + 1), false);
			clock.advance(Duration.ofMinutes(11));
		}
		assertEquals(List.of("版本 7", "版本 6", "版本 5", "版本 4", "版本 3"), bodies(history));

		history.record(NOTE, "版本 7", "别的", true);
		assertEquals(5, history.list(NOTE).size(), "和最新一份一样：不重复存");
		assertEquals("版本 7", bodies(history).get(0));
		history.record(NOTE, "没变", "没变", true);
		assertEquals("版本 7", bodies(history).get(0), "正文没变：不存");
	}

	@Test
	void encryptingAPlainNotePurgesItsPlainHistoryButCiphertextHistoryIsKept() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "明文秘密", "明文秘密 2", false);
		clock.advance(Duration.ofMinutes(11));
		history.record(NOTE, "明文秘密 2", "MPAL1:abc", false);
		assertEquals(List.of("明文秘密"), bodies(history), "明文 → 密文这一次不留明文，旧历史等正文写好再清");
		assertTrue(history.purgePlain(NOTE));
		assertTrue(history.list(NOTE).isEmpty());

		history.record(NOTE, "MPAL1:abc", "MPAL1:def", false);
		NoteHistory.Version version = history.list(NOTE).get(0);
		assertTrue(version.encrypted());
		clock.advance(Duration.ofMinutes(11));
		history.record(NOTE, "MPAL1:def", "解除加密后的明文", false);
		assertEquals(List.of("MPAL1:def", "MPAL1:abc"), bodies(history));
	}

	@Test
	void versionIdsAreValidatedSoTheyCannotEscapeTheHistoryFolder() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "x", "y", false);
		for (String bad : new String[]{null, "", "../keyring", "2026-10-28T08-00-00.000Z/../x", "2026-10-28T08-00-00Z",
				"2026-10-28T08-00-00.000Z.md"}) {
			assertFalse(NoteHistory.validId(bad), String.valueOf(bad));
			assertTrue(history.read(NOTE, bad).isEmpty());
		}
		assertTrue(history.read(NOTE, "2026-10-28T09-00-00.000Z").isEmpty());
	}

	@Test
	void purgeRemovesEverythingForThatNoteOnly() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "a", "b", false);
		history.record(OTHER, "c", "d", false);
		history.purge(NOTE);
		history.purge(NOTE); // 没有也不报错
		assertTrue(history.list(NOTE).isEmpty());
		assertEquals(1, history.list(OTHER).size());
	}

	@Test
	void vaultWriteBodyRecordsTheOldBodyOnlyWhenTheFileReallyChanges() throws Exception {
		Vault v = new Vault(vault);
		NoteHistory history = history();
		v.setHistory(history);
		NoteSummary note = v.createNote("日记", "# 日记\n第一版\n");
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "# 日记\n第二版\n", null));
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "# 日记\n第二版\n", null)); // 没变：不记
		clock.advance(Duration.ofMinutes(1));
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "# 日记\n第三版\n", null, n -> true, true)); // 还原：一定记
		assertEquals(Vault.WriteResult.DENIED, v.writeBody(note.id(), "# 日记\n不许写\n", null, n -> false, true));
		List<NoteHistory.Version> versions = history.list(note.id());
		assertEquals(2, versions.size());
		assertEquals("# 日记\n第二版\n", history.read(note.id(), versions.get(0).id()).orElseThrow());
		assertEquals("# 日记\n第一版\n", history.read(note.id(), versions.get(1).id()).orElseThrow());
		// 历史放在隐藏的 .palace 里：不会被当成笔记扫进来
		v.scan();
		assertEquals(1, v.list().size());
	}

	@Test
	void vaultPurgesPlainHistoryAfterTheEncryptedBodyIsWrittenAndOnEveryLaterEncryptedSave() throws Exception {
		Vault v = new Vault(vault);
		NoteHistory history = history();
		v.setHistory(history);
		NoteSummary note = v.createNote("秘密", "明文第一版\n");
		v.writeBody(note.id(), "明文第二版\n", null);
		assertEquals(1, history.list(note.id()).size());
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "MPAL1:abc", null));
		assertTrue(history.list(note.id()).isEmpty(), "加密之后不能还有明文历史");

		// 上次没清干净（比如文件被占用）留下的明文：下次保存加密笔记时清掉；密文历史保留
		Path dir = vault.resolve(".palace/history/" + note.id());
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("2026-10-27T08-00-00.000Z.md"), "漏网的明文");
		Files.writeString(dir.resolve("2026-10-27T09-00-00.000Z.md"), "MPAL1:old");
		Files.writeString(dir.resolve(".palace-123.tmp"), "写了一半的明文");
		clock.advance(Duration.ofMinutes(11));
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "MPAL1:def", null));
		assertEquals(List.of("MPAL1:abc", "MPAL1:old"), history.list(note.id()).stream().map(ver -> {
			try {
				return history.read(note.id(), ver.id()).orElseThrow();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}).toList());
		assertFalse(Files.exists(dir.resolve(".palace-123.tmp")));
	}

	@Test
	void aRecentLeftoverPlainVersionDoesNotSwallowTheNextCiphertextVersion() throws Exception {
		NoteHistory history = history();
		Path dir = vault.resolve(".palace/history/" + NOTE);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("2026-10-28T07-58-00.000Z.md"), "两分钟前漏网的明文");
		// 合并判断用的是清理之前列出的版本，只看其中的密文版本：明文删不删得掉都不影响这次记下密文
		history.record(NOTE, "MPAL1:abc", "MPAL1:def", false);
		assertEquals(List.of("MPAL1:abc"), bodies(history));
	}

	@Test
	void restoreIsRefusedWhenTheCurrentBodyCannotBeSavedToHistoryButNormalSavesStillWork() throws Exception {
		Vault v = new Vault(vault);
		v.setHistory(history());
		NoteSummary note = v.createNote("日记", "第一版\n");
		// 历史文件夹的位置被一个文件占着：存不了历史
		Files.createDirectories(vault.resolve(".palace/history"));
		Files.writeString(vault.resolve(".palace/history/" + note.id()), "挡路");
		assertEquals(Vault.WriteResult.HISTORY_FAILED, v.writeBody(note.id(), "还原的版本\n", null, n -> true, true));
		assertEquals("第一版\n", v.readBody(note.id()).orElseThrow(), "还原没写入");
		assertEquals(Vault.WriteResult.OK, v.writeBody(note.id(), "普通保存\n", null));
		assertEquals("普通保存\n", v.readBody(note.id()).orElseThrow());
	}

	private static List<String> bodies(NoteHistory history) throws Exception {
		return history.list(NOTE).stream().map(v -> {
			try {
				return history.read(NOTE, v.id()).orElseThrow();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}).toList();
	}
}
