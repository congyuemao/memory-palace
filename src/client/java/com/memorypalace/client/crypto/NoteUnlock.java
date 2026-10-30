package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 用手里的钥匙解开笔记：选该要哪把钥匙、取出内容钥匙、解密。各个加密流程共用。 */
final class NoteUnlock {
	private NoteUnlock() {}

	/**
	 * 这篇需要的钥匙。MPAL2：刚用阅读密码核对通过的（别人的笔记）直接解；文件夹笔记要文件夹钥匙（输文件夹密码）；
	 * 有自己 u 锁的（设过阅读密码的笔记）要保险库主密钥。旧格式 MPAL1 要保险库主密钥。
	 * editable = false 又有阅读密码锁的是别人的笔记：钥匙已经清掉了（上锁了），不能拿自己的保险库密码去试，让玩家重新打开。
	 */
	static void unlockFor(UUID id, String stored, boolean editable, Runnable then) {
		if (!NoteEnvelope.isEnvelope(stored)) {
			ClientKeys.requireUnlocked(null, then);
			return;
		}
		if (ReadPasswords.matches(id, stored)) {
			then.run();
			return;
		}
		String folderLock = folderLockOf(stored);
		if (!editable && hasPasswordLock(stored)) {
			PasswordScreen.tell("message.memorypalace.read_password_again");
		} else if (folderLock != null) {
			FolderKeys.require(id, folderLock, null, then);
		} else if (hasUserLock(stored)) {
			ClientKeys.requireUnlocked(null, then);
		} else {
			PasswordScreen.tell("message.memorypalace.decrypt_failed"); // 不认识的锁
		}
	}

	/** 现在手里的钥匙能不能直接解开（热重载时用，不弹密码框）。 */
	static boolean canDecryptNow(UUID id, String stored) {
		if (NoteEnvelope.isEnvelope(stored)) {
			String lock = folderLockOf(stored);
			return ReadPasswords.matches(id, stored) || lock != null && FolderKeys.get(lock) != null
					|| hasUserLock(stored) && ClientKeys.unlocked();
		}
		return ClientKeys.unlocked();
	}

	static boolean hasUserLock(String envelope) {
		return hasLock(envelope, NoteEnvelope.Kind.USER);
	}

	private static boolean hasPasswordLock(String envelope) {
		return hasLock(envelope, NoteEnvelope.Kind.PASSWORD);
	}

	private static boolean hasLock(String envelope, NoteEnvelope.Kind kind) {
		try {
			return NoteEnvelope.locks(envelope).stream().anyMatch(l -> l.kind() == kind);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** u 锁的标识：自己的 UUID（还没进世界时用单人时代的 "host"）。 */
	static String selfId() {
		var player = Minecraft.getInstance().player;
		return player == null ? "host" : player.getUUID().toString();
	}

	/**
	 * 用手里的钥匙取出 MPAL2 笔记的内容钥匙：核对通过的阅读密码 → 文件夹钥匙 → 自己的保险库主密钥。
	 * 都没有 → null；有钥匙但打不开 → WrongKeyException。
	 */
	static ContentKey envelopeKey(UUID id, String stored) throws WrongKeyException {
		byte[] readKey = ReadPasswords.wrappingKeyFor(id, stored);
		if (readKey != null) {
			// （用阅读密码打开的一律只读，见 openNote 里的 takeAwaiting）
			try {
				return NoteEnvelope.unlock(id, stored, NoteEnvelope.passwordLock(stored).orElseThrow(), readKey);
			} finally {
				Arrays.fill(readKey, (byte) 0);
			}
		}
		String lock = folderLockOf(stored);
		MasterKey folderKey = lock == null ? null : FolderKeys.get(lock);
		if (folderKey != null) {
			byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
			try {
				return NoteEnvelope.unlock(id, stored, new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lock), wrapping);
			} finally {
				Arrays.fill(wrapping, (byte) 0);
			}
		}
		if (hasUserLock(stored) && ClientKeys.unlocked()) {
			return NoteEnvelope.unlockAsUser(id, stored, ClientKeys.key(), selfId());
		}
		return null;
	}

	/** MPAL2 笔记里文件夹锁的 ID；没有文件夹锁或格式不对时为 null。 */
	static String folderLockOf(String envelope) {
		try {
			return NoteEnvelope.locks(envelope).stream().filter(l -> l.kind() == NoteEnvelope.Kind.FOLDER)
					.map(NoteEnvelope.Lock::id).findFirst().orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static Optional<String> decrypt(UUID noteId, String stored) {
		if (NoteEnvelope.isEnvelope(stored)) {
			// 新格式：取出这篇的内容钥匙，记下来（保存时用它再加密；阅读密码打开的别人的笔记不能保存，记下只是为了显示）
			try {
				ContentKey contentKey = envelopeKey(noteId, stored);
				if (contentKey == null) {
					PasswordScreen.tell("message.memorypalace.decrypt_failed");
					return Optional.empty();
				}
				String plain;
				try {
					plain = NoteEnvelope.decrypt(noteId, stored, contentKey);
				} catch (WrongKeyException e) {
					contentKey.destroy();
					throw e;
				}
				NoteSealers.remember(noteId, contentKey, stored);
				ReadPasswords.confirmed(noteId); // 用阅读密码打开的：真解开了，才长期记着这把钥匙
				return Optional.of(plain);
			} catch (WrongKeyException | IllegalArgumentException | NoSuchElementException e) {
				PasswordScreen.tell("message.memorypalace.decrypt_failed");
				return Optional.empty();
			}
		}
		if (!NoteCipher.isEncrypted(stored)) {
			// 收件箱随手记：用解锁时解出来的收件箱私钥
			String plain = ClientKeys.openSealed(stored);
			if (plain == null) {
				PasswordScreen.tell("message.memorypalace.inbox_no_box");
				return Optional.empty();
			}
			return Optional.of(plain);
		}
		try {
			return Optional.of(NoteCipher.decrypt(ClientKeys.key(), noteId, stored));
		} catch (WrongKeyException e) {
			// 主密钥是对的却解不开：文件坏了、被改过，或者是复制出来的副本（ID 变了）
			PasswordScreen.tell("message.memorypalace.decrypt_failed");
			return Optional.empty();
		}
	}

	/** 用自己的钥匙解开这篇（需要时先输保险库密码或文件夹密码），在渲染线程把明文交给 then。 */
	static void withPlain(UUID id, String stored, Consumer<String> then) {
		Minecraft mc = Minecraft.getInstance();
		String folderLock = NoteEnvelope.isEnvelope(stored) ? folderLockOf(stored) : null;
		Runnable open = () -> {
			String plain;
			try {
				if (NoteEnvelope.isEnvelope(stored)) {
					ContentKey contentKey = envelopeKey(id, stored);
					if (contentKey == null) {
						throw new WrongKeyException("没有钥匙");
					}
					try {
						plain = NoteEnvelope.decrypt(id, stored, contentKey);
					} finally {
						contentKey.destroy();
					}
				} else {
					plain = NoteCipher.decrypt(ClientKeys.key(), id, stored);
				}
			} catch (WrongKeyException | IllegalArgumentException | NoSuchElementException e) {
				PasswordScreen.tell("message.memorypalace.decrypt_failed");
				mc.gui.setScreen(null);
				return;
			}
			then.accept(plain);
		};
		if (folderLock != null) {
			FolderKeys.require(id, folderLock, null, open);
		} else {
			ClientKeys.requireUnlocked(null, open);
		}
	}

	/** 正文开头一小段（换行变成空格），给确认框用。 */
	static String preview(String plain) {
		String flat = plain.strip().replaceAll("\\s+", " ");
		return flat.length() <= 80 ? flat : flat.substring(0, 79) + "…";
	}
}
