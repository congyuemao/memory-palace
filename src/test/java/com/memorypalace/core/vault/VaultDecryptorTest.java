package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultDecryptorTest {
	private static final char[] PASSWORD = "主密码-123456".toCharArray();
	private static final char[] FOLDER_PASSWORD = "文件夹密码-654321".toCharArray();
	private static final UUID OLD = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID NEW = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID LOCKED = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@TempDir
	static Path dir;
	static Path vault;
	static String recovery;
	static String folderRecovery;

	/** 一个笔记库：旧格式加密笔记、新格式加密笔记、设了文件夹密码的文件夹里一篇、一篇明文。 */
	@BeforeAll
	static void makeVault() throws Exception {
		vault = dir.resolve("memory-palace");
		Files.createDirectories(vault.resolve(".palace/history/x"));
		Files.createDirectories(vault.resolve("私密"));
		KeyRing.Created ring = KeyRing.create(PASSWORD, true);
		recovery = ring.recoveryKey();
		Files.writeString(vault.resolve(".palace/keyring"), ring.content());
		Files.writeString(vault.resolve("旧格式.md"), note(OLD, NoteCipher.encrypt(ring.key(), OLD, "# 旧格式\n秘密一\n")));
		ContentKey key = ContentKey.random();
		Files.writeString(vault.resolve("新格式.md"), note(NEW, NoteEnvelope.seal(NEW, key, "# 新格式\n秘密二\n",
				Map.of(NoteEnvelope.Lock.user("host"), NoteEnvelope.userWrappingKey(ring.key())))));

		KeyRing.Created folder = KeyRing.createFolder(FOLDER_PASSWORD, true);
		folderRecovery = folder.recoveryKey();
		Files.writeString(vault.resolve("私密/" + FolderLocks.FILE), folder.content());
		ContentKey key2 = ContentKey.random();
		Files.writeString(vault.resolve("私密/一.md"), note(LOCKED, NoteEnvelope.seal(LOCKED, key2, "# 一\n秘密三\n",
				Map.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, KeyRing.folderId(folder.content())),
						NoteEnvelope.folderWrappingKey(folder.key())))));
		Files.writeString(vault.resolve("明文.md"), "# 明文\n大家都能看\n");
		Files.writeString(vault.resolve(".palace/history/x/旧版本.md"), "不该导出");
	}

	private static String note(UUID id, String body) {
		return "---\npalace-id: " + id + "\n---\n" + body;
	}

	@Test
	void passwordDecryptsOwnNotesAndCopiesPlainOnes() throws Exception {
		String before = Files.readString(vault.resolve("旧格式.md"));
		VaultDecryptor.Result result = VaultDecryptor.decrypt(vault, dir.resolve("out-password"), PASSWORD.clone());
		Path out = result.output();
		assertEquals(2, result.decrypted());
		assertEquals(1, result.plain());
		assertEquals(List.of("私密/一.md"), result.failed(), "文件夹用的是另一个密码");
		assertEquals(note(OLD, "# 旧格式\n秘密一\n"), Files.readString(out.resolve("旧格式.md")));
		assertEquals(note(NEW, "# 新格式\n秘密二\n"), Files.readString(out.resolve("新格式.md")));
		assertEquals("# 明文\n大家都能看\n", Files.readString(out.resolve("明文.md")));
		assertFalse(Files.exists(out.resolve("私密/一.md")));
		assertFalse(Files.exists(out.resolve(".palace")), "历史版本、钥匙圈不导出");
		assertEquals(before, Files.readString(vault.resolve("旧格式.md")), "原文件不动");
	}

	@Test
	void recoveryKeyWorksToo() throws Exception {
		VaultDecryptor.Result result = VaultDecryptor.decrypt(vault, dir.resolve("out-recovery"), recovery.toLowerCase().toCharArray());
		assertEquals(2, result.decrypted());
	}

	@Test
	void folderPasswordOpensItsFolder() throws Exception {
		VaultDecryptor.Result result = VaultDecryptor.decrypt(vault, dir.resolve("out-folder"), FOLDER_PASSWORD.clone());
		assertEquals(1, result.decrypted());
		assertEquals(note(LOCKED, "# 一\n秘密三\n"), Files.readString(result.output().resolve("私密/一.md")));
		VaultDecryptor.Result byRecovery = VaultDecryptor.decrypt(vault, dir.resolve("out-folder-recovery"), folderRecovery.toCharArray());
		assertEquals(1, byRecovery.decrypted());
	}

	@Test
	void wrongPasswordOpensNothing() throws Exception {
		VaultDecryptor.Result result = VaultDecryptor.decrypt(vault, dir.resolve("out-wrong"), "不对的密码".toCharArray());
		assertEquals(0, result.keys());
		assertEquals(0, result.decrypted());
		assertEquals(3, result.failed().size());
	}

	@Test
	void neverWritesIntoAnExistingFolder() throws Exception {
		Path existing = Files.createDirectories(dir.resolve("out-existing"));
		assertThrows(java.io.IOException.class, () -> VaultDecryptor.decrypt(vault, existing, PASSWORD.clone()));
	}
}
