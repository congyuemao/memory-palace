package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InboxTest {
	@TempDir
	Path dir;

	private static final ZoneOffset MELBOURNE = ZoneOffset.ofHours(11);
	private static final ZoneOffset CHINA = ZoneOffset.ofHours(8);
	private static final OffsetDateTime T = OffsetDateTime.of(2026, 10, 27, 14, 32, 0, 0, MELBOURNE);
	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void firstPlayerCaptureCreatesOwnedDailyInbox() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		String path = "players/" + ALICE + "/收件箱/2026-10-27.md";
		assertFalse(Files.exists(dir.resolve("players/" + ALICE + "/收件箱")));

		NoteSummary note = Inbox.append(vault, ALICE, T, "第一条");
		assertEquals(path, Inbox.pathFor(ALICE, T));
		assertEquals(path, note.relativePath());
		assertTrue(Files.isRegularFile(dir.resolve(path)));
		String text = Files.readString(dir.resolve(path), StandardCharsets.UTF_8);
		assertEquals(ALICE.toString(), FrontMatter.parse(text).fields().get("palace-owner"));
		assertEquals(ALICE, note.access().owner());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 第一条\n", vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void secondPlayerCaptureAppendsToTheSameDailyInbox() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary first = Inbox.append(vault, ALICE, T, "第一条");
		NoteSummary second = Inbox.append(vault, ALICE, T.plusMinutes(38), "第二条");

		assertEquals(first.id(), second.id());
		assertEquals(first.relativePath(), second.relativePath());
		assertEquals(ALICE, second.access().owner());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 第一条\n- 15:10 第二条\n",
				vault.readBody(second.id()).orElseThrow());
		try (var files = Files.list(dir.resolve("players/" + ALICE + "/收件箱"))) {
			assertEquals(1, files.count());
		}
	}

	@Test
	void differentPlayersHaveSeparateDailyInboxes() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary alice = Inbox.append(vault, ALICE, T, "Alice 的想法");
		NoteSummary bob = Inbox.append(vault, BOB, T, "Bob 的想法");

		assertEquals("players/" + ALICE + "/收件箱/2026-10-27.md", alice.relativePath());
		assertEquals("players/" + BOB + "/收件箱/2026-10-27.md", bob.relativePath());
		assertNotEquals(alice.id(), bob.id());
		assertTrue(Files.isRegularFile(dir.resolve(alice.relativePath())));
		assertTrue(Files.isRegularFile(dir.resolve(bob.relativePath())));
		assertEquals(ALICE, alice.access().owner());
		assertEquals(BOB, bob.access().owner());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) Alice 的想法\n", vault.readBody(alice.id()).orElseThrow());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) Bob 的想法\n", vault.readBody(bob.id()).orElseThrow());
	}

	@Test
	void hostCaptureCreatesRootInboxWithoutOwner() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, null, T, "房主的想法");

		assertEquals("收件箱/2026-10-27.md", note.relativePath());
		assertNull(note.access().owner());
		String text = Files.readString(dir.resolve("收件箱/2026-10-27.md"), StandardCharsets.UTF_8);
		assertFalse(FrontMatter.parse(text).fields().containsKey("palace-owner"));
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 房主的想法\n", vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void oldOverloadAndExplicitNullOwnerAppendToTheSameInbox() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary first = Inbox.append(vault, T, "第一条");
		NoteSummary second = Inbox.append(vault, null, T.plusMinutes(1), "第二条");
		NoteSummary third = Inbox.append(vault, T.plusMinutes(2), "第三条");

		assertEquals(first.id(), second.id());
		assertEquals(first.id(), third.id());
		assertEquals("收件箱/2026-10-27.md", third.relativePath());
		assertNull(first.access().owner());
		assertNull(third.access().owner());
		String text = Files.readString(dir.resolve(third.relativePath()), StandardCharsets.UTF_8);
		assertFalse(FrontMatter.parse(text).fields().containsKey("palace-owner"));
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 第一条\n- 14:33 第二条\n- 14:34 第三条\n",
				vault.readBody(third.id()).orElseThrow());
	}

	@Test
	void playerCannotAppendToInboxOwnedBySomeoneElse() throws IOException {
		Path file = dir.resolve("players/" + ALICE + "/收件箱/2026-10-27.md");
		Files.createDirectories(file.getParent());
		String original = "---\npalace-id: " + UUID.randomUUID() + "\npalace-owner: " + BOB + "\n---\n# 别人的笔记\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();

		assertThrows(Inbox.NotYoursException.class, () -> Inbox.append(vault, ALICE, T, "我的想法"));
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void playerCannotAppendToUnownedInboxInTheirArea() throws IOException {
		Path file = dir.resolve("players/" + ALICE + "/收件箱/2026-10-27.md");
		Files.createDirectories(file.getParent());
		String original = "---\npalace-id: " + UUID.randomUUID() + "\n---\n# 无主的笔记\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();

		assertThrows(Inbox.NotYoursException.class, () -> Inbox.append(vault, ALICE, T, "我的想法"));
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void hostCannotAppendToOwnedRootInbox() throws IOException {
		Path file = dir.resolve("收件箱/2026-10-27.md");
		Files.createDirectories(file.getParent());
		String original = "---\npalace-id: " + UUID.randomUUID() + "\npalace-owner: " + ALICE + "\n---\n# 玩家的笔记\n";
		Files.writeString(file, original, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();

		assertThrows(Inbox.NotYoursException.class, () -> Inbox.append(vault, null, T, "房主的想法"));
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
		assertThrows(Inbox.NotYoursException.class, () -> Inbox.append(vault, T, "房主的想法"));
		assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void folderForSeparatesHostAndPlayerInboxes() {
		assertEquals("收件箱", Inbox.folderFor(null));
		assertEquals("players/" + ALICE + "/收件箱", Inbox.folderFor(ALICE));
	}

	@Test
	void pathForUsesLocalDateNearMidnight() {
		// 本地已经是 27 日，UTC 仍然是 26 日。
		OffsetDateTime now = OffsetDateTime.parse("2026-10-27T00:15:00+11:00");
		assertEquals("收件箱/2026-10-27.md", Inbox.pathFor(null, now));
		assertEquals("players/" + ALICE + "/收件箱/2026-10-27.md", Inbox.pathFor(ALICE, now));
	}

	@Test
	void recognizesRootAndPlayerInboxFoldersAndTheirChildren() {
		for (String folder : new String[]{"收件箱", "收件箱/子",
				"players/" + ALICE + "/收件箱", "players/" + ALICE + "/收件箱/子"}) {
			assertTrue(Inbox.isInboxFolder(folder), folder);
		}
	}

	@Test
	void rejectsFoldersOutsideRootAndPlayerInboxes() {
		for (String folder : new String[]{"", "笔记", "收件箱x", "players", "players/收件箱",
				"players/" + ALICE, "players/" + ALICE + "/笔记", "a/收件箱"}) {
			assertFalse(Inbox.isInboxFolder(folder), folder);
		}
	}

	@Test
	void noFileUntilFirstCaptureAndFirstEntryIsTagged() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		assertFalse(Files.exists(dir.resolve("收件箱")), "没记东西就不能有收件箱文件");

		NoteSummary note = Inbox.append(vault, T, "特征值和奇异值的关系？");
		assertEquals("收件箱/2026-10-27.md", note.relativePath());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 特征值和奇异值的关系？\n", vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void sameTimezoneNotTaggedAgainAndNewFileNextDay() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary first = Inbox.append(vault, T, "第一条");
		NoteSummary second = Inbox.append(vault, T.plusMinutes(38), "第二条");
		assertEquals(first.id(), second.id());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 第一条\n- 15:10 第二条\n", vault.readBody(first.id()).orElseThrow());

		// 三天后：只多出那一天的文件，中间没有空文件
		NoteSummary later = Inbox.append(vault, T.plusDays(3), "隔了三天");
		assertEquals("收件箱/2026-10-30.md", later.relativePath());
		try (var files = Files.list(dir.resolve("收件箱"))) {
			assertEquals(2, files.count());
		}
	}

	@Test
	void flyingToChinaTagsTheChangeAndUsesLocalDate() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		Inbox.append(vault, T, "出发");
		// 同一时刻往后 5 小时，到了中国：本地时间 16:32（UTC+8），还是 27 日
		OffsetDateTime landed = T.plusHours(5).withOffsetSameInstant(CHINA);
		NoteSummary note = Inbox.append(vault, landed, "落地了");
		Inbox.append(vault, landed.plusMinutes(10), "打车");
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 出发\n- 16:32 (UTC+8) 落地了\n- 16:42 打车\n",
				vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void crossingTheDatelineGoesToTheLocalDate() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		// 新西兰 28 日早上 8 点（UTC+13）起飞往东，6 小时后到夏威夷（UTC-10）：当地还是 27 日下午 3 点
		OffsetDateTime nz = OffsetDateTime.of(2026, 10, 28, 8, 0, 0, 0, ZoneOffset.ofHours(13));
		NoteSummary first = Inbox.append(vault, nz, "起飞");
		NoteSummary second = Inbox.append(vault, nz.plusHours(6).withOffsetSameInstant(ZoneOffset.ofHours(-10)), "到了，日子倒回去了");
		assertEquals("收件箱/2026-10-28.md", first.relativePath());
		assertEquals("收件箱/2026-10-27.md", second.relativePath());
		assertEquals("# 收件箱 2026-10-27\n\n- 15:00 (UTC-10) 到了，日子倒回去了\n", vault.readBody(second.id()).orElseThrow());
	}

	@Test
	void labelsForUnusualOffsets() {
		assertEquals("UTC", Inbox.label(ZoneOffset.UTC));
		assertEquals("UTC+8", Inbox.label(CHINA));
		assertEquals("UTC+5:30", Inbox.label(ZoneOffset.ofHoursMinutes(5, 30)));
		assertEquals("UTC+5:45", Inbox.label(ZoneOffset.ofHoursMinutes(5, 45)));
		assertEquals("UTC-3:30", Inbox.label(ZoneOffset.ofHoursMinutes(-3, -30)));
	}

	@Test
	void halfHourOffsetIsReadBackCorrectly() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		ZoneOffset india = ZoneOffset.ofHoursMinutes(5, 30);
		NoteSummary note = Inbox.append(vault, T.withOffsetSameInstant(india), "一");
		Inbox.append(vault, T.plusMinutes(1).withOffsetSameInstant(india), "二");
		assertTrue(vault.readBody(note.id()).orElseThrow().endsWith("(UTC+5:30) 一\n- 09:03 二\n"));
	}

	@Test
	void flattensNewlinesAndRejectsEmpty() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "  第一行\r\n第二行\n ");
		assertTrue(vault.readBody(note.id()).orElseThrow().endsWith("- 14:32 (UTC+11) 第一行  第二行\n"));
		assertThrows(IllegalArgumentException.class, () -> Inbox.append(vault, T, " \n "));
		assertThrows(IllegalArgumentException.class, () -> Inbox.append(vault, T, ""));
	}

	@Test
	void addsMissingNewlineBeforeAppending() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "一");
		// 用户在外部编辑器里改过，末尾没有换行
		vault.writeBody(note.id(), "# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 一\n手写的一行", null);
		Inbox.append(vault, T.plusMinutes(1), "二");
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 (UTC+11) 一\n手写的一行\n- 14:33 二\n", vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void fileWithoutAnyTagGetsTaggedOnce() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "一");
		// 旧版本写的收件箱没有时区标注
		vault.writeBody(note.id(), "# 收件箱 2026-10-27\n\n- 14:00 旧的一条\n", null);
		Inbox.append(vault, T, "二");
		Inbox.append(vault, T.plusMinutes(1), "三");
		assertEquals("# 收件箱 2026-10-27\n\n- 14:00 旧的一条\n- 14:32 (UTC+11) 二\n- 14:33 三\n",
				vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void refusesWhenFull() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "一");
		vault.writeBody(note.id(), "x".repeat(Inbox.MAX_CHARS), null);
		assertThrows(Inbox.FullException.class, () -> Inbox.append(vault, T, "二"));
		assertEquals(Inbox.MAX_CHARS, vault.readBody(note.id()).orElseThrow().length(), "满了就不能再写");
	}

	@Test
	void refusesEncryptedInbox() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "一");
		var key = KeyRing.create("password1".toCharArray(), false).key();
		vault.writeBody(note.id(), NoteCipher.encrypt(key, note.id(), "x"), null);
		assertThrows(IllegalStateException.class, () -> Inbox.append(vault, T, "二"));
	}
}
