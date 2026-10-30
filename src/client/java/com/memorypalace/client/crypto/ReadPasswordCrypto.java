package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.vault.Vault;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 单篇阅读密码：作者设置、取消阅读密码时重新加密自己的笔记。 */
final class ReadPasswordCrypto {
	private ReadPasswordCrypto() {}

	/**
	 * /palace sharepassword（set）、/palace unsharepassword：解开这篇 → （设密码时）输两遍新阅读密码 →
	 * 换一把新的内容钥匙重新加密（自己的锁 + 新的 p 锁，或者只有自己的锁）→ 存回去。
	 * 换内容钥匙是为了真正收回：以前用旧密码看过的人手里的内容钥匙，对新密文没用。
	 * 旧格式 MPAL1 的笔记这时改成新格式（只这一篇，是玩家自己要的）。
	 */
	static void changeReadPassword(UUID id, String title, String stored, boolean set) {
		if (!Ciphertext.isEncrypted(stored)) {
			PasswordScreen.tell("message.memorypalace.crypto_failed");
			return;
		}
		NoteUnlock.withPlain(id, stored, plain -> {
			if (!set) {
				resealAndSave(id, stored, plain, null, "message.memorypalace.read_password_removed");
				return;
			}
			Minecraft.getInstance().gui.setScreen(ReadPasswordPrompts.newReadPassword(title, NoteUnlock.preview(plain),
					lock -> resealAndSave(id, stored, plain, lock, "message.memorypalace.read_password_set")));
		});
	}

	/**
	 * 作者自己能重新包上的锁：文件夹笔记 → 原来那把 f 锁；其余 → 自己的 u 锁。钥匙已经不在手里（中途上锁了）→ null。
	 * 返回的包装钥匙由调用方清零。
	 */
	private static Map<NoteEnvelope.Lock, byte[]> ownLocks(String stored) {
		Map<NoteEnvelope.Lock, byte[]> locks = new LinkedHashMap<>();
		String folderLock = NoteEnvelope.isEnvelope(stored) ? NoteUnlock.folderLockOf(stored) : null;
		if (folderLock != null) {
			MasterKey folderKey = FolderKeys.get(folderLock);
			if (folderKey == null) {
				return null;
			}
			locks.put(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, folderLock), NoteEnvelope.folderWrappingKey(folderKey));
		} else {
			if (!ClientKeys.unlocked()) {
				return null;
			}
			locks.put(NoteEnvelope.Lock.user(NoteUnlock.selfId()), NoteEnvelope.userWrappingKey(ClientKeys.key()));
		}
		return locks;
	}

	/** 新内容钥匙 + 自己的锁（+ 阅读密码锁）重新加密，存回去；存好才记下新的内容钥匙。 */
	private static void resealAndSave(UUID id, String stored, String plain, NoteEnvelope.PasswordLock passwordLock, String doneKey) {
		Minecraft mc = Minecraft.getInstance();
		Map<NoteEnvelope.Lock, byte[]> locks = ownLocks(stored);
		if (locks == null) {
			if (passwordLock != null) {
				Arrays.fill(passwordLock.wrappingKey(), (byte) 0);
			}
			PasswordScreen.tell("message.memorypalace.crypto_failed");
			mc.gui.setScreen(null);
			return;
		}
		if (passwordLock != null) {
			locks.put(passwordLock.lock(), passwordLock.wrappingKey());
		}
		ContentKey contentKey = ContentKey.random();
		String sealed;
		try {
			sealed = NoteEnvelope.seal(id, contentKey, plain, locks);
		} finally {
			locks.values().forEach(k -> Arrays.fill(k, (byte) 0));
		}
		int generation = ClientKeys.generation();
		NoteSealers.pending(contentKey); // 服务端一直不回应时，上锁照样把它清零
		NoteSaver.save(id, sealed, Vault.hash(stored), result -> {
			NoteSealers.settled(contentKey);
			if (generation != ClientKeys.generation()) {
				// 回包之前已经上锁 / 离开过世界：新内容钥匙不能在上锁之后又留进内存
				contentKey.destroy();
				if ("OK".equals(result.result())) {
					CryptoClient.rememberEncrypted(id);
					PasswordScreen.tell(doneKey);
				}
				return;
			}
			if ("OK".equals(result.result())) {
				CryptoClient.rememberEncrypted(id);
				NoteSealers.remember(id, contentKey, sealed);
				PasswordScreen.tell(doneKey);
			} else {
				contentKey.destroy();
				PasswordScreen.tell("message.memorypalace.crypto_failed");
			}
			mc.gui.setScreen(null);
		});
	}
}
