package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class InboxTest {
	@TempDir
	Path dir;

	private static final LocalDateTime T = LocalDateTime.of(2026, 10, 27, 14, 32);

	@Test
	void noFileUntilFirstCapture() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		assertFalse(Files.exists(dir.resolve("收件箱")), "没记东西就不能有收件箱文件");

		NoteSummary note = Inbox.append(vault, T, "特征值和奇异值的关系？");
		assertEquals("收件箱/2026-10-27.md", note.relativePath());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 特征值和奇异值的关系？\n", vault.readBody(note.id()).orElseThrow());
	}

	@Test
	void appendsToSameDayAndNewFileNextDay() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary first = Inbox.append(vault, T, "第一条");
		NoteSummary second = Inbox.append(vault, T.plusMinutes(38), "第二条");
		assertEquals(first.id(), second.id());
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 第一条\n- 15:10 第二条\n", vault.readBody(first.id()).orElseThrow());

		// 三天后：只多出那一天的文件，中间没有空文件
		NoteSummary later = Inbox.append(vault, T.plusDays(3), "隔了三天");
		assertEquals("收件箱/2026-10-30.md", later.relativePath());
		try (var files = Files.list(dir.resolve("收件箱"))) {
			assertEquals(2, files.count());
		}
	}

	@Test
	void flattensNewlinesAndRejectsEmpty() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "  第一行\r\n第二行\n ");
		assertTrue(vault.readBody(note.id()).orElseThrow().endsWith("- 14:32 第一行  第二行\n"));
		assertThrows(IllegalArgumentException.class, () -> Inbox.append(vault, T, " \n "));
		assertThrows(IllegalArgumentException.class, () -> Inbox.append(vault, T, ""));
	}

	@Test
	void addsMissingNewlineBeforeAppending() throws IOException {
		var vault = new Vault(dir);
		vault.scan();
		NoteSummary note = Inbox.append(vault, T, "一");
		// 用户在外部编辑器里改过，末尾没有换行
		vault.writeBody(note.id(), "# 收件箱 2026-10-27\n\n- 14:32 一\n手写的一行", null);
		Inbox.append(vault, T.plusMinutes(1), "二");
		assertEquals("# 收件箱 2026-10-27\n\n- 14:32 一\n手写的一行\n- 14:33 二\n", vault.readBody(note.id()).orElseThrow());
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
