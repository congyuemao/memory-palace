package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** 单篇笔记用保险库主密钥加密（/palace encrypt、newsecret）和解除加密（🔓）。 */
final class PlainCrypto {
	private PlainCrypto() {}

	static void encrypt(CryptoAction kind, UUID id, Component title, String body) {
		Minecraft mc = Minecraft.getInstance();
		ClientKeys.requireUnlocked(null, () -> {
			String cipher = NoteCipher.encrypt(ClientKeys.key(), id, body);
			NoteSaver.save(id, cipher, Vault.hash(body), result -> {
				if (!"OK".equals(result.result())) {
					PasswordScreen.tell("message.memorypalace.crypto_failed");
					mc.gui.setScreen(null);
					return;
				}
				CryptoClient.rememberEncrypted(id); // 自己加密的，自然知道它是加密笔记
				PasswordScreen.tell("message.memorypalace.encrypted");
				mc.gui.setScreen(kind == CryptoAction.ENCRYPT_NEW && ClientKeys.unlocked()
						? new EditorScreen(id, title, body, true, result.newHash())
						: null);
			});
		});
	}

	/** 解除加密（意图已经核对过）：即使已经解锁也要重新输一次密码。 */
	static void decrypt(UUID id, String title, String body) {
		if (NoteEnvelope.isEnvelope(body) && NoteUnlock.folderLockOf(body) != null) {
			// 文件夹里的笔记不能单独解除加密（服务端也不让在被锁的文件夹里存明文）；要整个文件夹解除密码
			PasswordScreen.tell("message.memorypalace.folder_note_decrypt");
			return;
		}
		if (NoteEnvelope.isEnvelope(body) && !NoteUnlock.hasUserLock(body)) {
			PasswordScreen.tell("message.memorypalace.decrypt_failed");
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientKeys.withKeyring(keyring -> {
			if (keyring.isEmpty()) {
				PasswordScreen.tell("message.memorypalace.no_keyring");
				return;
			}
			mc.gui.setScreen(VaultPasswords.verify(keyring, PasswordScreen.t("verify_decrypt", title), null,
					(password, key) -> decryptAndSave(id, title, body, key)));
		});
	}

	private static void decryptAndSave(UUID id, String title, String cipher, MasterKey key) {
		Minecraft mc = Minecraft.getInstance();
		String plain;
		try {
			if (NoteEnvelope.isEnvelope(cipher)) {
				// 设过阅读密码的笔记（新格式、有自己的 u 锁）：解除加密后阅读密码也就没了
				ContentKey contentKey = NoteEnvelope.unlockAsUser(id, cipher, key, NoteUnlock.selfId());
				try {
					plain = NoteEnvelope.decrypt(id, cipher, contentKey);
				} finally {
					contentKey.destroy();
				}
			} else {
				plain = NoteCipher.decrypt(key, id, cipher);
			}
		} catch (WrongKeyException e) {
			// 解不开就什么都不写：绝不能把密文覆盖成空的或错的东西
			PasswordScreen.tell("message.memorypalace.decrypt_failed");
			mc.gui.setScreen(null);
			return;
		}
		// 交出明文前再让玩家看一眼"到底要公开的是什么"：服务端可能把别的笔记换进来，
		// 所以显示的是真正解出来的开头，而不是服务端说的标题
		mc.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (!confirmed) {
				mc.gui.setScreen(null);
				return;
			}
			NoteSaver.saveDecrypted(id, plain, Vault.hash(cipher), result -> {
				boolean ok = "OK".equals(result.result());
				if (ok) {
					CryptoClient.forgetEncrypted(id); // 是自己解除的：以后它以明文出现是正常的
				}
				PasswordScreen.tell(ok ? "message.memorypalace.decrypted" : "message.memorypalace.crypto_failed");
				mc.gui.setScreen(null);
			});
		}, Component.translatable("screen.memorypalace.confirm_decrypt.title", title),
				Component.translatable("screen.memorypalace.confirm_decrypt.message", NoteUnlock.preview(plain)),
				Component.translatable("screen.memorypalace.confirm_decrypt.yes"),
				Component.translatable("screen.memorypalace.confirm_decrypt.no")));
	}
}
