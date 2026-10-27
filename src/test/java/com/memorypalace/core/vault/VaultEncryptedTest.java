package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
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
	void duplicateEncryptedFileIsNeverRewritten() throws IOException {
		UUID id = UUID.randomUUID();
		MasterKey key = KeyRing.create("password1".toCharArray(), false).key();
		String content = "---\npalace-id: " + id + "\npalace-encrypted: v1\n---\n" + NoteCipher.encrypt(key, id, "日记");
		// 副本的文件名排在原文件前面：以前会把"原文件"改 ID，导致它再也解不开
		Files.writeString(dir.resolve("b-原文件.md"), content);
		Files.writeString(dir.resolve("a-副本.md"), content);
		var vault = new Vault(dir);
		vault.scan();

		// 两个文件都一个字节都不改
		assertEquals(content, read("b-原文件.md"));
		assertEquals(content, read("a-副本.md"));
		// 只收录一个（字典序第一个），另一个暂不收录
		assertEquals(1, vault.list().size());
		assertEquals(id, vault.list().get(0).id());
	}

	@Test
	void encryptedFileWithoutIdIsNotRewritten() throws IOException {
		MasterKey key = KeyRing.create("password1".toCharArray(), false).key();
		String content = "---\npalace-encrypted: v1\n---\n" + NoteCipher.encrypt(key, UUID.randomUUID(), "x");
		Files.writeString(dir.resolve("丢了ID.md"), content);
		var vault = new Vault(dir);
		vault.scan();
		assertEquals(content, read("丢了ID.md"), "加密笔记没有 ID 时也不能给它分配新 ID（密文绑定的是原 ID）");
		assertTrue(vault.list().isEmpty());
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

	@Test
	void mpal2BodyWithoutFlagIsScannedAsEncryptedUsingTheFileName() throws IOException {
		UUID id = UUID.randomUUID();
		String cipher = envelope(id, "# Secret heading\nPrivate content");
		String content = "---\npalace-id: " + id + "\n---\n \t\n" + cipher + "\n";
		Files.writeString(dir.resolve("diary.md"), content, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();

		NoteSummary note = vault.find(id).orElseThrow();
		assertTrue(note.encrypted());
		assertEquals("diary", note.title());
		assertEquals(" \t\n" + cipher + "\n", vault.readBody(id).orElseThrow());
		assertEquals(content, read("diary.md"), "Scanning must preserve externally written ciphertext");
	}

	@Test
	void writingMpal2SetsTheFlagPreservesMetadataAndHidesTheTitle() throws IOException {
		UUID id = UUID.randomUUID();
		String plain = "# Secret heading\nPrivate content";
		Files.writeString(dir.resolve("diary.md"),
				"---\ntags: [private]\npalace-id: " + id + "\n---\n" + plain, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();
		assertFalse(vault.find(id).orElseThrow().encrypted());
		assertEquals("Secret heading", vault.find(id).orElseThrow().title());

		String cipher = envelope(id, plain);
		assertEquals(Vault.WriteResult.OK, vault.writeBody(id, cipher, Vault.hash(plain)));
		String file = read("diary.md");
		var parsed = FrontMatter.parse(file);
		assertEquals("v1", parsed.fields().get("palace-encrypted"));
		assertEquals("[private]", parsed.fields().get("tags"));
		assertEquals(id, parsed.id());
		assertEquals(cipher, parsed.body());
		assertFalse(file.contains("Secret heading"));
		assertTrue(vault.find(id).orElseThrow().encrypted());
		assertEquals("diary", vault.find(id).orElseThrow().title());

		vault.scan();
		assertTrue(vault.find(id).orElseThrow().encrypted());
		assertEquals("diary", vault.find(id).orElseThrow().title());
	}

	@Test
	void writingPlaintextOverMpal2RemovesTheEncryptionFlag() throws IOException {
		UUID id = UUID.randomUUID();
		String plain = "# Secret heading\nPrivate content";
		String cipher = envelope(id, plain);
		Files.writeString(dir.resolve("diary.md"), "---\ntags: [private]\npalace-id: " + id
				+ "\npalace-encrypted: v1\n---\n" + cipher, StandardCharsets.UTF_8);
		var vault = new Vault(dir);
		vault.scan();
		assertTrue(vault.find(id).orElseThrow().encrypted());

		assertEquals(Vault.WriteResult.OK, vault.writeBody(id, plain, Vault.hash(cipher)));
		var parsed = FrontMatter.parse(read("diary.md"));
		assertFalse(parsed.fields().containsKey("palace-encrypted"));
		assertEquals("---\ntags: [private]\npalace-id: " + id + "\n---\n" + plain, read("diary.md"));
		assertEquals(plain, vault.readBody(id).orElseThrow());
		assertFalse(vault.find(id).orElseThrow().encrypted());
		assertEquals("Secret heading", vault.find(id).orElseThrow().title());
	}

	private static String envelope(UUID id, String plaintext) {
		MasterKey folderKey = new MasterKey(new byte[32]);
		ContentKey key = ContentKey.random();
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
		try {
			var lock = new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, "abcdefghijklmnopqrstuv");
			return NoteEnvelope.seal(id, key, plaintext, Map.of(lock, wrapping));
		} finally {
			Arrays.fill(wrapping, (byte) 0);
			key.destroy();
			folderKey.destroy();
		}
	}
}
