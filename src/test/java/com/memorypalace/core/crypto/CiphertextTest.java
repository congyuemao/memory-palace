package com.memorypalace.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CiphertextTest {
	@Test
	void recognizesMpal1AndMpal2WithSurroundingWhitespace() {
		UUID note = UUID.randomUUID();
		MasterKey master = new MasterKey(new byte[32]);
		ContentKey key = ContentKey.random();
		byte[] wrapping = NoteEnvelope.userWrappingKey(master);
		try {
			String legacy = NoteCipher.encrypt(master, note, "# Secret\nBody");
			String envelope = NoteEnvelope.seal(note, key, "# Secret\nBody",
					Map.of(NoteEnvelope.Lock.user("host"), wrapping));
			for (String body : List.of(legacy, envelope)) {
				assertTrue(Ciphertext.isEncrypted(body));
				assertTrue(Ciphertext.isEncrypted(" \t\r\n" + body + "\n\t "));
			}
		} finally {
			NoteCipher.wipe(wrapping);
			key.destroy();
			master.destroy();
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"plain text", "# Plain note\nBody", " \t\r\n", "text MPAL1:...", "text MPAL2:..."})
	void plainOrMissingBodiesAreNotEncrypted(String body) {
		assertFalse(Ciphertext.isEncrypted(body));
	}

	@Test
	void sealedInboxLinesDoNotMakeTheBodyAnEncryptedNote() {
		var keys = InboxCipher.generate();
		try {
			String first = InboxCipher.seal(keys.publicKey(), "First capture");
			String second = InboxCipher.seal(keys.publicKey(), "Second capture");
			assertFalse(Ciphertext.isEncrypted(first));
			assertFalse(Ciphertext.isEncrypted(" \n" + first + "\n" + second + "\n"));
			assertFalse(Ciphertext.isEncrypted("# Inbox\n\n" + first + "\n- 14:32 Plain capture\n" + second + "\n"));
		} finally {
			NoteCipher.wipe(keys.privateKey());
		}
	}
}
