package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.vault.FolderCopy;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.function.Consumer;

/** 抄本：抄加密笔记时用自己的钥匙解开原笔记，再按新笔记的 ID 重新加密。 */
final class CopyCrypto {
	private CopyCrypto() {}

	/**
	 * 抄进被锁的文件夹（见 FolderCopy）：原正文是密文就先用自己的钥匙解开，再用目标所在文件夹的钥匙
	 * （需要时输文件夹密码）按目标 ID 加密，保存时核对目标还是占位的样子——被外部改过就冲突，不覆盖。
	 * 取消、失败都不写任何东西；再右键抄本可以重试。存好之后服务端把抄本换成笔记书。
	 */
	static void copyIntoFolder(UUID targetId, FolderCopy.Packed packed) {
		if (packed.source().equals(targetId)) {
			return;
		}
		Consumer<String> sealAndSave = plain -> FolderKeys.withLock(targetId, data -> {
			String lockId;
			try {
				lockId = "ok".equals(data.status()) ? KeyRing.folderId(data.content()) : null;
			} catch (IllegalArgumentException e) {
				lockId = null;
			}
			if (lockId == null) {
				PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
				return;
			}
			String lock = lockId;
			FolderKeys.require(targetId, lock, null, () -> {
				MasterKey folderKey = FolderKeys.get(lock);
				if (folderKey == null) {
					PasswordScreen.tell("message.memorypalace.copy_retry");
					return;
				}
				FolderCopy.Sealed sealed = FolderCrypto.sealWithFolderLock(targetId, plain, lock, folderKey);
				int generation = ClientKeys.generation();
				NoteSealers.pending(sealed.key());
				NoteSaver.save(targetId, sealed.text(), packed.targetHash(), result -> {
					NoteSealers.settled(sealed.key());
					boolean ok = "OK".equals(result.result());
					if (ok && generation == ClientKeys.generation()) {
						CryptoClient.rememberEncrypted(targetId);
						NoteSealers.remember(targetId, sealed.key(), sealed.text());
					} else {
						sealed.key().destroy();
						if (ok) {
							CryptoClient.rememberEncrypted(targetId);
						}
					}
					// 成功的提示由服务端换笔记书时发；这里只说没成功的原因
					if (!ok) {
						PasswordScreen.tell("CONFLICT".equals(result.result())
								? "message.memorypalace.copy_conflict" : "message.memorypalace.copy_retry");
					}
					Minecraft.getInstance().gui.setScreen(null);
				});
			});
		});
		if (Ciphertext.isEncrypted(packed.body())) {
			NoteUnlock.withPlain(packed.source(), packed.body(), sealAndSave);
		} else {
			sealAndSave.accept(packed.body());
		}
	}

	/** "原笔记ID:原密文" 里的原笔记 ID；格式不对时用一个不会和任何右键对上的 ID。 */
	static UUID copySource(String packed) {
		int colon = packed.indexOf(':');
		try {
			return colon < 0 ? new UUID(0, 0) : UUID.fromString(packed.substring(0, colon));
		} catch (IllegalArgumentException e) {
			return new UUID(0, 0);
		}
	}

	/**
	 * 抄一篇加密笔记：packed = "原笔记ID:原密文"。用自己的钥匙解开原笔记（需要时输保险库密码或文件夹密码），
	 * 再用保险库主密钥按新笔记的 ID 加密（密文绑定笔记 ID，不能直接复制），存进服务端刚建好的新笔记。
	 */
	static void copyEncrypted(UUID newId, String packed) {
		int colon = packed.indexOf(':');
		UUID sourceId;
		try {
			sourceId = colon < 0 ? null : UUID.fromString(packed.substring(0, colon));
		} catch (IllegalArgumentException e) {
			sourceId = null;
		}
		String cipher = colon < 0 ? "" : packed.substring(colon + 1);
		if (sourceId == null || sourceId.equals(newId) || !Ciphertext.isEncrypted(cipher)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		NoteUnlock.withPlain(sourceId, cipher, plain -> ClientKeys.requireUnlocked(null, () -> {
			String sealed = NoteCipher.encrypt(ClientKeys.key(), newId, plain);
			// 新笔记是服务端刚建的（只有一行默认标题），不核对旧内容
			NoteSaver.save(newId, sealed, null, result -> {
				boolean ok = "OK".equals(result.result());
				if (ok) {
					CryptoClient.rememberEncrypted(newId);
				}
				PasswordScreen.tell(ok ? "message.memorypalace.copied_encrypted" : "message.memorypalace.crypto_failed");
				// 只关掉这次弹出的密码框：在工作台里取出抄本时不用输密码，不能把工作台关掉
				if (mc.gui.screen() instanceof PasswordScreen) {
					mc.gui.setScreen(null);
				}
			});
		}));
	}
}
