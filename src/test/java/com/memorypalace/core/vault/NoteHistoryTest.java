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
	void everySaveIsKeptAndOlderVersionsAreStoredAsDiffs() throws Exception {
		NoteHistory history = history();
		String body = "# 你是一个一个\n";
		for (int i = 1; i <= 6; i++) {
			String next = body + "%d%d%d%d%d\n".formatted(i, i, i, i, i);
			assertTrue(history.record(NOTE, body, next, false));
			body = next;
			clock.advance(Duration.ofSeconds(20)); // 没退出编辑器、隔一会儿保存一次
		}
		List<String> bodies = bodies(history);
		assertEquals(6, bodies.size(), "每次保存都留一份");
		assertEquals("# 你是一个一个\n", bodies.get(5));
		assertEquals("# 你是一个一个\n11111\n22222\n33333\n44444\n", bodies.get(1));
		assertEquals("# 你是一个一个\n11111\n22222\n33333\n44444\n55555\n", bodies.get(0));

		Path dir = vault.resolve(".palace/history/" + NOTE);
		List<NoteHistory.Version> versions = history.list(NOTE);
		assertTrue(Files.isRegularFile(dir.resolve(versions.get(0).id() + ".md")), "最新的一份存全文");
		for (int i = 1; i < versions.size(); i++) {
			Path diff = dir.resolve(versions.get(i).id() + ".diff");
			assertTrue(Files.isRegularFile(diff), "更早的存差异");
			assertFalse(Files.exists(dir.resolve(versions.get(i).id() + ".md")));
			assertTrue(Files.readString(diff).startsWith("--- " + versions.get(i - 1).id() + "\n+++ " + versions.get(i).id() + "\n"));
		}
		assertEquals(1, versions.get(0).added());
		assertEquals(0, versions.get(0).removed());
		assertEquals(-1, versions.get(5).added(), "最早的一份没有更早的可比");
	}

	@Test
	void diffsRoundTripAwkwardText() throws Exception {
		String[] texts = {"", "\n", "a", "a\n", "a\nb", "\n\n\n", "- 列表\n+ 加号\n--- 横线\n+++ x\n@@ -1,2 +3,4 @@\n",
				"第一行\r\n中间有回车\n\n空行之后", "x\n".repeat(50), "x\ny\n".repeat(25) + "尾巴"};
		for (String from : texts) {
			for (String to : texts) {
				String diff = NoteHistory.unifiedDiff("2026-10-28T08-00-01.000Z", from, "2026-10-28T08-00-00.000Z", to);
				List<String> lines = new java.util.ArrayList<>(List.of(diff.split("\n", -1)));
				lines.removeLast();
				assertEquals(to, NoteHistory.applyDiff(from, lines), () -> "从「" + from + "」到「" + to + "」");
			}
		}
	}

	@Test
	void legacyFullVersionsStillWorkAndCorruptDiffsAreSkipped() throws Exception {
		Path dir = vault.resolve(".palace/history/" + NOTE);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("2026-10-27T08-00-00.000Z.md"), "旧格式一");
		Files.writeString(dir.resolve("2026-10-27T09-00-00.000Z.md"), "旧格式二");
		NoteHistory history = history();
		history.record(NOTE, "新的一份\n", "现在\n", false);
		assertEquals(List.of("新的一份\n", "旧格式二", "旧格式一"), bodies(history));

		// 差异文件被改坏了：这一份读不出来、列表里跳过，其他的不受影响
		String newest = history.list(NOTE).get(0).id();
		clock.advance(Duration.ofSeconds(1));
		history.record(NOTE, "再新一份\n", "现在\n", false);
		Path diff = dir.resolve(newest + ".diff");
		assertTrue(Files.isRegularFile(diff));
		Files.writeString(diff, Files.readString(diff).replace("-再新一份", "-对不上"));
		assertTrue(history.read(NOTE, newest).isEmpty());
		assertEquals(List.of("再新一份\n", "旧格式一"), bodies(history), "旧格式二已改存成基于它的差异，一起读不出来；再往前的全文不受影响");
	}

	@Test
	void longDiffChainsAreBrokenByFullCopies() throws Exception {
		NoteHistory history = history();
		String body = "";
		for (int i = 0; i < 50; i++) {
			String next = body + i + "\n";
			history.record(NOTE, body, next, false);
			body = next;
			clock.advance(Duration.ofSeconds(1));
		}
		Path dir = vault.resolve(".palace/history/" + NOTE);
		List<NoteHistory.Version> versions = history.list(NOTE);
		assertEquals(NoteHistory.RECENT + 1, versions.size(), "最近 30 份，加上更早那几份里同一小时的最后一份");
		int run = 0;
		for (NoteHistory.Version v : versions) {
			run = Files.exists(dir.resolve(v.id() + ".diff")) ? run + 1 : 0;
			assertTrue(run <= NoteHistory.MAX_CHAIN, "连着的差异不超过 MAX_CHAIN 份");
		}
		for (int i = 0; i < NoteHistory.RECENT; i++) {
			assertEquals(49 - i, history.read(NOTE, versions.get(i).id()).orElseThrow().split("\n", -1).length - 1);
		}
	}

	@Test
	void retentionKeepsRecentHourlyAndDailyVersions() throws Exception {
		NoteHistory history = history();
		// 10 天里每 10 分钟保存一次
		String body = "开始";
		for (int i = 0; i < 10 * 24 * 6; i++) {
			String next = "第 " + i + " 次";
			history.record(NOTE, body, next, false);
			body = next;
			clock.advance(Duration.ofMinutes(10));
		}
		List<NoteHistory.Version> versions = history.list(NOTE);
		Instant now = clock.instant().minus(Duration.ofMinutes(10)); // 最后一次保存（整理）时的时刻
		java.util.Set<String> hours = new java.util.HashSet<>();
		java.util.Set<String> days = new java.util.HashSet<>();
		for (int i = NoteHistory.RECENT; i < versions.size(); i++) {
			Instant t = versions.get(i).time();
			if (Duration.between(t, now).compareTo(NoteHistory.HOURLY_FOR) <= 0) {
				assertTrue(hours.add(t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString()), "一小时只留一份");
			} else {
				assertTrue(days.add(t.atZone(ZoneOffset.UTC).toLocalDate().toString()), "一天只留一份");
			}
		}
		assertTrue(hours.size() >= 7 * 24 - 6, "7 天内大约每小时一份：" + hours.size());
		assertTrue(days.size() >= 3 && days.size() <= 4, "更早的 3 天每天一份：" + days);
		assertEquals(versions.size(), bodies(history).size(), "留下来的都读得出来");
	}

	@Test
	void spammingSavesOnlyChurnsTheRecentVersions() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "三天前", "x", false);
		clock.advance(Duration.ofDays(1));
		history.record(NOTE, "两天前", "x", false);
		clock.advance(Duration.ofDays(2));
		// 攻击者一口气保存 300 次（服务端本来就限速，这里假设全都漏过来了）
		for (int i = 0; i < 300; i++) {
			history.record(NOTE, "刷 " + i, "刷 " + (i + 1), false);
			clock.advance(Duration.ofMillis(3));
		}
		List<String> bodies = bodies(history);
		assertEquals(List.of("两天前", "三天前"), bodies.subList(bodies.size() - 2, bodies.size()), "更早的挤不掉");
		assertTrue(bodies.size() <= NoteHistory.RECENT + 3, "刷出来的只占最近那几份：" + bodies.size());
	}

	@Test
	void historySizeIsCapped() throws Exception {
		NoteHistory history = history();
		for (int i = 0; i < 25; i++) {
			// 超过 DIFF_LIMIT：只能存全文
			history.record(NOTE, String.valueOf((char) ('a' + i)).repeat(NoteHistory.DIFF_LIMIT + 1), "x", false);
			clock.advance(Duration.ofSeconds(1));
		}
		long total = 0;
		try (var files = Files.list(vault.resolve(".palace/history/" + NOTE))) {
			for (Path f : files.toList()) {
				total += Files.size(f);
			}
		}
		assertTrue(total <= NoteHistory.MAX_BYTES, "总大小 " + total);
		assertEquals(String.valueOf('y').repeat(NoteHistory.DIFF_LIMIT + 1), bodies(history).get(0));
	}

	@Test
	void skipsDuplicatesAndNoOps() throws Exception {
		NoteHistory history = history();
		history.record(NOTE, "版本 1", "版本 2", false);
		history.record(NOTE, "版本 1", "别的", true);
		assertEquals(List.of("版本 1"), bodies(history), "和最新一份一样：不重复存");
		history.record(NOTE, "没变", "没变", true);
		assertEquals(List.of("版本 1"), bodies(history), "正文没变：不存");
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
