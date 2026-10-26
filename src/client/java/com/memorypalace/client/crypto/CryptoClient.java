package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.client.NoteScreen;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 客户端处理笔记正文：收齐分块、打开时（加密笔记先解锁再解密）、热重载时解密，
 * 以及执行服务端转来的"加密 / 解除加密 / 上锁"。
 */
public final class CryptoClient {
	// 正在接收的正文：笔记 ID → 已收到的块
	private static final Map<UUID, String[]> INCOMING = new HashMap<>();

	private CryptoClient() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.KeyringData.TYPE,
				(payload, context) -> ClientKeys.onKeyringData(payload));
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.KeyringWriteResult.TYPE,
				(payload, context) -> ClientKeys.onKeyringWriteResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.Action.TYPE, (payload, context) -> handle(payload));
		ClientPlayNetworking.registerGlobalReceiver(OpenNotePayload.TYPE, (payload, context) -> receive(payload));
		// 离开世界一律上锁，丢掉没收完的东西
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			INCOMING.clear();
			ClientKeys.disconnect();
		}));
	}

	/** 收一块正文；收齐了就打开（或刷新）。 */
	private static void receive(OpenNotePayload part) {
		if (part.total() < 1 || part.index() < 0 || part.index() >= part.total()) {
			return;
		}
		String[] parts = INCOMING.get(part.id());
		if (parts == null || parts.length != part.total() || part.index() == 0) {
			parts = new String[part.total()];
			INCOMING.put(part.id(), parts);
		}
		parts[part.index()] = part.part();
		for (String p : parts) {
			if (p == null) {
				return;
			}
		}
		INCOMING.remove(part.id());
		openNote(part.id(), Component.literal(part.title()), String.join("", parts), part.refresh());
	}

	/** 正文收齐了：明文直接打开；密文先解锁再解密。 */
	private static void openNote(UUID id, Component title, String stored, boolean refresh) {
		Minecraft mc = Minecraft.getInstance();
		String storedHash = Vault.hash(stored);
		boolean encrypted = NoteCipher.isEncrypted(stored);
		if (refresh) {
			if (!(mc.gui.screen() instanceof NoteScreen open) || !id.equals(open.noteId())) {
				return; // 没开着这篇，不用管
			}
			if (open.isEncrypted() != encrypted) {
				// 文件在外部被加密/解密了：界面状态对不上，不能原地刷新（否则可能以明文身份编辑密文内容），关掉重开
				mc.gui.setScreen(null);
				openNote(id, title, stored, false);
			} else if (!encrypted) {
				open.updateBody(stored, storedHash);
			} else if (ClientKeys.unlocked()) {
				decrypt(id, stored).ifPresent(plain -> open.updateBody(plain, storedHash));
			}
			return;
		}
		if (!encrypted) {
			mc.gui.setScreen(new NoteScreen(id, title, stored, false, storedHash));
			return;
		}
		ClientKeys.requireUnlocked(null, () -> decrypt(id, stored).ifPresentOrElse(
				plain -> mc.gui.setScreen(new NoteScreen(id, title, plain, true, storedHash)),
				() -> mc.gui.setScreen(null)));
	}

	private static Optional<String> decrypt(UUID noteId, String stored) {
		try {
			return Optional.of(NoteCipher.decrypt(ClientKeys.key(), noteId, stored));
		} catch (WrongKeyException e) {
			// 主密钥是对的却解不开：文件坏了、被改过，或者是复制出来的副本（ID 变了）
			PasswordScreen.tell("message.memorypalace.decrypt_failed");
			return Optional.empty();
		}
	}

	private static void handle(CryptoPayloads.Action action) {
		Minecraft mc = Minecraft.getInstance();
		UUID id = action.noteId();
		Component title = Component.literal(action.title());
		String body = action.body();
		switch (action.action()) {
			case "lock" -> ClientKeys.lock(true);
			case "encrypt", "encrypt_new" -> ClientKeys.requireUnlocked(null, () -> {
				String cipher = NoteCipher.encrypt(ClientKeys.key(), id, body);
				NoteSaver.save(id, cipher, Vault.hash(body), result -> {
					if (!"OK".equals(result.result())) {
						PasswordScreen.tell("message.memorypalace.crypto_failed");
						mc.gui.setScreen(null);
						return;
					}
					PasswordScreen.tell("message.memorypalace.encrypted");
					mc.gui.setScreen("encrypt_new".equals(action.action()) && ClientKeys.unlocked()
							? new EditorScreen(id, title, body, true, result.newHash())
							: null);
				});
			});
			// 解除加密 = 把明文永久写回硬盘，所以即使已经解锁也要重新输一次密码
			case "decrypt" -> ClientKeys.withKeyring(keyring -> {
				if (keyring.isEmpty()) {
					PasswordScreen.tell("message.memorypalace.no_keyring");
					return;
				}
				mc.gui.setScreen(PasswordScreen.verify(keyring, PasswordScreen.t("verify_decrypt", action.title()), null,
						(password, key) -> decryptAndSave(id, body, key)));
			});
			default -> {}
		}
	}

	private static void decryptAndSave(UUID id, String cipher, MasterKey key) {
		Minecraft mc = Minecraft.getInstance();
		String plain;
		try {
			plain = NoteCipher.decrypt(key, id, cipher);
		} catch (WrongKeyException e) {
			// 解不开就什么都不写：绝不能把密文覆盖成空的或错的东西
			PasswordScreen.tell("message.memorypalace.decrypt_failed");
			mc.gui.setScreen(null);
			return;
		}
		NoteSaver.saveDecrypted(id, plain, Vault.hash(cipher), result -> {
			PasswordScreen.tell("OK".equals(result.result())
					? "message.memorypalace.decrypted" : "message.memorypalace.crypto_failed");
			mc.gui.setScreen(null);
		});
	}
}
