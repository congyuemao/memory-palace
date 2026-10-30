package com.memorypalace.client.crypto;

import com.memorypalace.client.HistoryScreen;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.EditPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Consumer;

/** 历史版本：查看、还原（加密笔记先解开旧版本，再用现在的钥匙重新加密）。 */
final class HistoryCrypto {
	private HistoryCrypto() {}

	/** /palace history 里点了"查看"：只读打开这个版本（加密的先用自己的钥匙解开）。 */
	static void viewVersion(UUID id, String title, String version) {
		Minecraft mc = Minecraft.getInstance();
		Component screenTitle = Component.translatable("screen.memorypalace.history_title", title);
		if (Ciphertext.isEncrypted(version)) {
			NoteUnlock.withPlain(id, version, plain -> mc.gui.setScreen(new HistoryScreen(screenTitle, plain, true)));
		} else if (InboxCipher.hasSealedLines(version)) {
			// 收件箱的旧版本：随手记密文行用收件箱私钥解开
			ClientKeys.requireUnlocked(null, () -> {
				String plain = ClientKeys.openSealed(version);
				if (plain == null) {
					PasswordScreen.tell("message.memorypalace.inbox_no_box");
					mc.gui.setScreen(null);
					return;
				}
				mc.gui.setScreen(new HistoryScreen(screenTitle, plain, true));
			});
		} else {
			mc.gui.setScreen(new HistoryScreen(screenTitle, version, false));
		}
	}

	/**
	 * /palace history 里点了"还原"。packed = "现在正文的长度:现在的正文旧版本"。
	 * 明文笔记：确认后直接把旧版本存回去。加密笔记：解开旧版本，用现在的内容钥匙（或现在的主密钥）重新加密再存——
	 * 锁以现在的为准，取消掉的阅读密码不会跟着旧版本回来。每次都先让玩家看一眼旧版本的开头再确认。
	 */
	static void restoreVersion(UUID id, String title, String packed) {
		int colon = packed.indexOf(':');
		int length;
		try {
			length = colon < 0 ? -1 : Integer.parseInt(packed.substring(0, colon));
		} catch (NumberFormatException e) {
			length = -1;
		}
		if (length < 0 || colon + 1 + length > packed.length()) {
			return;
		}
		String current = packed.substring(colon + 1, colon + 1 + length);
		String version = packed.substring(colon + 1 + length);
		if (!Ciphertext.isEncrypted(current)) {
			// 明文笔记不能还原成密文版本（解开再存就是以明文存回）；已知的加密笔记被说成明文时也不认
			if (Ciphertext.isEncrypted(version) || CryptoClient.isKnownEncrypted(id)) {
				PasswordScreen.tell("command.memorypalace.history_restore_encrypted");
				return;
			}
			confirmRestore(title, version, () -> NoteSaver.saveRestored(id, version, Vault.hash(current), result -> restored(result)));
			return;
		}
		// 加密笔记：先确认现在这篇解得开（钥匙在手），再解开旧版本（旧版本没加密时就是它本身）
		NoteUnlock.withPlain(id, current, ignored -> {
			Consumer<String> withOld = oldPlain -> confirmRestore(title, oldPlain, () -> resealRestored(id, current, oldPlain));
			if (Ciphertext.isEncrypted(version)) {
				NoteUnlock.withPlain(id, version, withOld);
			} else {
				withOld.accept(version);
			}
		});
	}

	private static void confirmRestore(String title, String plain, Runnable go) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new ConfirmScreen(yes -> {
			mc.gui.setScreen(null);
			if (yes) {
				go.run();
			}
		}, Component.translatable("screen.memorypalace.confirm_restore.title", title),
				Component.translatable("screen.memorypalace.confirm_restore.message", NoteUnlock.preview(plain)),
				Component.translatable("screen.memorypalace.confirm_restore.yes"),
				Component.translatable("screen.memorypalace.confirm_decrypt.no")));
	}

	/** 用现在这篇的钥匙把旧版本的明文重新加密，存回去（确认之后才取钥匙：确认期间可能已经上锁）。 */
	private static void resealRestored(UUID id, String current, String oldPlain) {
		String sealed;
		ContentKey contentKey = null;
		try {
			if (NoteEnvelope.isEnvelope(current)) {
				contentKey = NoteUnlock.envelopeKey(id, current);
				if (contentKey == null) {
					throw new WrongKeyException("已上锁");
				}
				sealed = NoteEnvelope.withBody(id, current, contentKey, oldPlain);
			} else {
				if (!ClientKeys.unlocked()) {
					throw new WrongKeyException("已上锁");
				}
				sealed = NoteCipher.encrypt(ClientKeys.key(), id, oldPlain);
			}
		} catch (WrongKeyException | IllegalArgumentException | NoSuchElementException e) {
			if (contentKey != null) {
				contentKey.destroy();
			}
			PasswordScreen.tell("message.memorypalace.crypto_failed");
			return;
		}
		ContentKey key = contentKey;
		int generation = ClientKeys.generation();
		if (key != null) {
			NoteSealers.pending(key);
		}
		NoteSaver.saveRestored(id, sealed, Vault.hash(current), result -> {
			if (key != null) {
				NoteSealers.settled(key);
			}
			if (key != null && "OK".equals(result.result()) && generation == ClientKeys.generation()) {
				NoteSealers.remember(id, key, sealed); // 之后编辑保存在这份新正文的基础上
			} else if (key != null) {
				key.destroy();
			}
			restored(result);
		});
	}

	private static void restored(EditPayloads.SaveResult result) {
		PasswordScreen.tell(switch (result.result()) {
			case "OK" -> "message.memorypalace.restored";
			case "HISTORY_FAILED" -> "message.memorypalace.restore_history_failed";
			default -> "message.memorypalace.crypto_failed";
		});
	}
}
