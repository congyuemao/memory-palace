package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultEncryptedTest {
	@TempDir
	Path dir;

	private String read(String rel) throws IOException {
		return Files.readString(dir.resolve(rel), StandardCharsets.UTF_8);
	}

	@Test
	void encryptingSetsFlagAndHidesTitle() throws IOException {
		UUID id = UUID.randomUUID();
		Files.writeString(dir.resolve("日记.md"), "---\ntags: [私密]\npalace-id: " + id + "\n---\n# 今天很开心\n内容");
		var vault = new Vault(dir);
		vault.scan();
		assertFalse(vault.find(id).orElseThrow().encrypted());
		assertEquals("今天很开心", vault.find(id).orElseThrow().title());

		MasterKey key = KeyRing.create("password1".toCharArray(), false).key();
		String cipher = NoteCipher.encrypt(key, id, "# 今天很开心\n内容");
		assertEquals(Vault.WriteResult.OK, vault.writeBody(id, cipher, null));

		String file = read("日记.md");
		var parsed = FrontMatter.parse(file);
		assertEquals("v1", parsed.fields().get("palace-encrypted"));
		assertEquals("[私密]", parsed.fields().get("tags"), "其他字段不动");
		assertEquals(id, parsed.id());
		assertFalse(file.contains("今天很开心"));

		NoteSummary note = vault.find(id).orElseThrow();
		assertTrue(note.encrypted());
		assertEquals("日记", note.title(), "加密后标题用文件名");

		// 重新扫描也能认出来
		vault.scan();
		assertTrue(vault.find(id).orElseThrow().encrypted());
		assertEquals("日记", vault.find(id).orElseThrow().title());
	}

	@Test
	void decryptingRemovesFlag() throws IOException {
		UUID id = UUID.randomUUID();
		MasterKey key = KeyRing.create("password1".toCharArray(), false).key();
		Files.writeString(dir.resolve("a.md"), "---\npalace-id: " + id + "\npalace-encrypted: v1\n---\n"
				+ NoteCipher.encrypt(key, id, "# 秘密\n内容"));
		var vault = new Vault(dir);
		vault.scan();
		assertTrue(vault.find(id).orElseThrow().encrypted());

		assertEquals(Vault.WriteResult.OK, vault.writeBody(id, "# 秘密\n内容", null));
		var parsed = FrontMatter.parse(read("a.md"));
		assertNull(parsed.fields().get("palace-encrypted"));
		assertEquals("---\npalace-id: " + id + "\n---\n# 秘密\n内容", read("a.md"));
		assertFalse(vault.find(id).orElseThrow().encrypted());
		assertEquals("秘密", vault.find(id).orElseThrow().title());
	}

	@Test
	void encryptedBodyWithoutFlagIsStillRecognized() throws IOException {
		UUID id = UUID.randomUUID();
		MasterKey key = KeyRing.create("password1".toCharArray(), false).key();
		// 比如用户在外部编辑器里不小心删了 palace-encrypted 那一行
		Files.writeString(dir.resolve("b.md"), "---\npalace-id: " + id + "\n---\n" + NoteCipher.encrypt(key, id, "x"));
		var vault = new Vault(dir);
		vault.scan();
		assertTrue(vault.find(id).orElseThrow().encrypted());
		assertEquals("b", vault.find(id).orElseThrow().title());
	}
}
