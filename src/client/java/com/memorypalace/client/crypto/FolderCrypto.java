package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.FolderCopy;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoPayloads;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.FolderPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 文件夹独立密码：上锁、继续加密、改密码、解除，以及在被锁的文件夹里新建笔记。
 * 服务端逐篇发来的"用这把文件夹钥匙加密 / 解密这篇"，只在 FolderBatch 正在做这把锁的那一批时才认。
 */
final class FolderCrypto {
	// 加密文件夹时遇到的旧格式 MPAL1 笔记：要先解锁保险库才能解开再改用文件夹钥匙；保险库还锁着时先排队，解锁一次后一起处理
	private static final List<CryptoPayloads.Action> WAITING_FOR_VAULT = new ArrayList<>();

	private FolderCrypto() {}

	/** 离开世界：丢掉排队等保险库解锁的笔记。 */
	static void disconnect() {
		WAITING_FOR_VAULT.clear();
	}

	/** /palace lockfolder：设文件夹密码 → 锁文件存好 → 请服务端把文件夹里的笔记逐篇发来加密。 */
	static void lockFolder(String folder) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(FolderPasswords.setupFolder(folder, null, created -> {
			String lockId = KeyRing.folderId(created.content());
			FolderKeys.put(lockId, created.key());
			mc.gui.setScreen(null);
			startEncrypting(folder, lockId, "message.memorypalace.folder_locked");
		}));
	}

	/**
	 * 对已经上锁的文件夹再执行 /palace lockfolder：上次没加密完（限速、中途上锁、离开世界……）就接着加密剩下的。
	 * 需要时输文件夹密码（忘了可以用恢复密钥）。已经用这把锁加密过的笔记服务端不会再发，不会重复加密。
	 */
	static void resumeFolder(String folder, String lock) {
		Minecraft mc = Minecraft.getInstance();
		String lockId = folderIdOf(lock);
		if (lockId == null) {
			PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
			return;
		}
		if (FolderKeys.get(lockId) != null) {
			startEncrypting(folder, lockId, "message.memorypalace.folder_resuming");
			return;
		}
		Consumer<MasterKey> go = key -> {
			FolderKeys.put(lockId, key);
			mc.gui.setScreen(null);
			startEncrypting(folder, lockId, "message.memorypalace.folder_resuming");
		};
		mc.gui.setScreen(FolderPasswords.unlockFolder(folder, lock, null, go));
	}

	private static void startEncrypting(String folder, String lockId, String messageKey) {
		FolderBatch.start(FolderBatch.Kind.ENCRYPT, folder, lockId, null);
		PasswordScreen.tell(messageKey);
		ClientPlayNetworking.send(new FolderPayloads.EncryptAll(folder));
	}

	/** 服务端发来文件夹里的一篇（明文、旧格式 MPAL1、或设过阅读密码的新格式）：用这把文件夹钥匙重新加密，排进队列逐篇存回去。 */
	static void encryptFolderNote(CryptoPayloads.Action action) {
		String lockId = FolderBatch.lockId();
		if (lockId == null || !FolderBatch.active(FolderBatch.Kind.ENCRYPT, lockId)) {
			return; // 不是正在加密的那一批（玩家没刚锁好 / 没要求继续）：不认
		}
		MasterKey folderKey = FolderKeys.get(lockId);
		String body = action.body();
		boolean ownEnvelope = NoteEnvelope.isEnvelope(body) && NoteUnlock.folderLockOf(body) == null && NoteUnlock.hasUserLock(body);
		if (folderKey == null || NoteEnvelope.isEnvelope(body) && !ownEnvelope) {
			// 文件夹钥匙没了（中途上锁）；或已经是别的文件夹锁着的新格式：不动，算失败
			FolderBatch.failed();
			return;
		}
		if (NoteCipher.isEncrypted(body) || ownEnvelope) {
			// 旧格式加密笔记、设过阅读密码的笔记（新格式、自己的 u 锁）：要先用保险库主密钥解开，改用文件夹钥匙后阅读密码也就没了。
			// 保险库还锁着时排队，解锁一次后一起处理
			if (!ClientKeys.unlocked()) {
				WAITING_FOR_VAULT.add(action);
				FolderBatch.hold();
				if (WAITING_FOR_VAULT.size() == 1) {
					ClientKeys.requireUnlocked(null, () -> {
						Minecraft.getInstance().gui.setScreen(null);
						List<CryptoPayloads.Action> queued = List.copyOf(WAITING_FOR_VAULT);
						WAITING_FOR_VAULT.clear();
						queued.forEach(a -> {
							encryptFolderNote(a);
							FolderBatch.release();
						});
					});
				}
				return;
			}
			try {
				queueFolderSeal(action.noteId(), decryptOwn(action.noteId(), body), body, lockId, folderKey);
			} catch (WrongKeyException e) {
				FolderBatch.failed();
			}
			return;
		}
		queueFolderSeal(action.noteId(), body, body, lockId, folderKey);
	}

	/** 用保险库主密钥（必须已解锁）解开自己的加密笔记：旧格式 MPAL1，或带自己 u 锁的新格式。 */
	private static String decryptOwn(UUID id, String body) throws WrongKeyException {
		if (!NoteEnvelope.isEnvelope(body)) {
			return NoteCipher.decrypt(ClientKeys.key(), id, body);
		}
		ContentKey contentKey = NoteEnvelope.unlockAsUser(id, body, ClientKeys.key(), NoteUnlock.selfId());
		try {
			return NoteEnvelope.decrypt(id, body, contentKey);
		} finally {
			contentKey.destroy();
		}
	}

	/** 用文件夹钥匙加密好，排进 FolderBatch 逐篇保存（存成功才记下内容钥匙）。 */
	private static void queueFolderSeal(UUID id, String plain, String stored, String lockId, MasterKey folderKey) {
		FolderCopy.Sealed sealed = sealWithFolderLock(id, plain, lockId, folderKey);
		FolderBatch.add(new FolderBatch.SaveJob(id, sealed.text(), Vault.hash(stored), ok -> {
			if (ok) {
				CryptoClient.rememberEncrypted(id);
				NoteSealers.remember(id, sealed.key(), sealed.text());
			} else {
				sealed.key().destroy();
			}
		}));
	}

	/** 在被锁的文件夹里新建的笔记：解锁文件夹（需要时输文件夹密码）→ 用文件夹钥匙加密 → 存好后打开编辑器。 */
	static void newFolderNote(UUID id, Component title, String body) {
		Minecraft mc = Minecraft.getInstance();
		FolderKeys.withLock(id, data -> {
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
			FolderKeys.require(id, lock, null, () -> saveWithFolderLock(id, body, body, lock, FolderKeys.get(lock), result -> {
				if ("OK".equals(result.result()) && NoteSealers.isEnvelope(id)) {
					mc.gui.setScreen(new EditorScreen(id, title, body, true, result.newHash()));
				} else {
					mc.gui.setScreen(null);
				}
			}));
		});
	}

	/** /palace folderpassword：输当前文件夹密码（忘了就用恢复密钥）→ 设新密码 → 锁存好。 */
	static void changeFolderPassword(String folder, String lock) {
		Minecraft mc = Minecraft.getInstance();
		String lockId = folderIdOf(lock);
		if (lockId == null) {
			PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
			return;
		}
		Consumer<MasterKey> done = key -> {
			FolderKeys.put(lockId, key);
			PasswordScreen.tell("message.memorypalace.folder_password_changed");
			mc.gui.setScreen(null);
		};
		mc.gui.setScreen(FolderPasswords.verifyFolder(folder, lock, PasswordScreen.t("verify_folder_password"), null,
				key -> mc.gui.setScreen(FolderPasswords.newFolderPassword(folder, lock, key, null, done)),
				done)); // 用恢复密钥重设时已经设过新密码了
	}

	/**
	 * /palace unlockfolder：输文件夹密码（忘了就用恢复密钥）→ 确认"里面的笔记会以明文存回服务器" →
	 * 请服务端把还用着这把锁的笔记逐篇发来解密 → 全部存好后删锁文件。
	 */
	static void unlockFolder(String folder, String lock) {
		Minecraft mc = Minecraft.getInstance();
		String lockId = folderIdOf(lock);
		if (lockId == null) {
			PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
			return;
		}
		Consumer<MasterKey> confirm = key -> mc.gui.setScreen(new ConfirmScreen(yes -> {
			mc.gui.setScreen(null);
			if (!yes) {
				key.destroy();
				return;
			}
			FolderKeys.put(lockId, key);
			FolderBatch.start(FolderBatch.Kind.DECRYPT, folder, lockId, lock);
			PasswordScreen.tell("message.memorypalace.folder_unlocking");
			ClientPlayNetworking.send(new FolderPayloads.DecryptAll(folder));
		}, Component.translatable("screen.memorypalace.confirm_unlock_folder.title", folder),
				Component.translatable("screen.memorypalace.confirm_unlock_folder.message"),
				Component.translatable("screen.memorypalace.confirm_unlock_folder.yes"),
				Component.translatable("screen.memorypalace.confirm_decrypt.no")));
		mc.gui.setScreen(FolderPasswords.verifyFolder(folder, lock, PasswordScreen.t("verify_unlock_folder"), null, confirm, confirm));
	}

	/**
	 * 解除文件夹密码时服务端发来的一篇：用这把文件夹钥匙解开，排进 FolderBatch 逐篇存回明文。
	 * 全部存好、服务端也说发完了，FolderBatch 才删锁；有失败就不删（锁还在，没解开的笔记照样能用文件夹密码打开）。
	 */
	static void decryptFolderNote(CryptoPayloads.Action action) {
		String lockId = FolderBatch.lockId();
		UUID id = action.noteId();
		String body = action.body();
		// 只认正在解除的那一批，只解这把锁的笔记：服务端不能借机让客户端解开别的文件夹（或者别的锁）的笔记
		if (lockId == null || !FolderBatch.active(FolderBatch.Kind.DECRYPT, lockId)
				|| !NoteEnvelope.isEnvelope(body) || !lockId.equals(NoteUnlock.folderLockOf(body))) {
			return;
		}
		MasterKey folderKey = FolderKeys.get(lockId);
		if (folderKey == null) {
			FolderBatch.failed();
			return;
		}
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
		String plain;
		try {
			ContentKey contentKey = NoteEnvelope.unlock(id, body, new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId), wrapping);
			try {
				plain = NoteEnvelope.decrypt(id, body, contentKey);
			} finally {
				contentKey.destroy();
			}
		} catch (WrongKeyException e) {
			FolderBatch.failed();
			return;
		} finally {
			Arrays.fill(wrapping, (byte) 0);
		}
		FolderBatch.add(new FolderBatch.SaveJob(id, plain, Vault.hash(body), ok -> {
			if (ok) {
				CryptoClient.forgetEncrypted(id); // 是自己解除的：以后它以明文出现是正常的
			}
		}));
	}

	private static String folderIdOf(String lock) {
		try {
			return KeyRing.folderId(lock);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 用文件夹钥匙给这篇加密：新的内容钥匙，只有一把 f 锁（保险库密码打不开）。 */
	static FolderCopy.Sealed sealWithFolderLock(UUID id, String plain, String lockId, MasterKey folderKey) {
		return FolderCopy.seal(id, plain, lockId, folderKey);
	}

	/**
	 * 加密单独一篇并马上存回去（在被锁的文件夹里新建笔记时）。stored 是服务端那边现在的正文（用来核对没被别人改过）。
	 * 存好后记下内容钥匙，之后编辑保存用。
	 */
	private static void saveWithFolderLock(UUID id, String plain, String stored, String lockId, MasterKey folderKey,
			Consumer<EditPayloads.SaveResult> after) {
		FolderCopy.Sealed s = sealWithFolderLock(id, plain, lockId, folderKey);
		ContentKey contentKey = s.key();
		String sealed = s.text();
		NoteSaver.save(id, sealed, Vault.hash(stored), result -> {
			if ("OK".equals(result.result())) {
				CryptoClient.rememberEncrypted(id);
				NoteSealers.remember(id, contentKey, sealed);
			} else {
				contentKey.destroy();
				PasswordScreen.tell("message.memorypalace.folder_encrypt_failed");
			}
			if (after != null) {
				after.accept(result);
			}
		});
	}
}
