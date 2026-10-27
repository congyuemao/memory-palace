package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.client.NoteScreen;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoPayloads;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.FolderPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端处理笔记正文：收齐分块、打开时（加密笔记先解锁再解密）、热重载时解密，
 * 以及执行服务端转来的"加密 / 解除加密 / 上锁"。
 */
public final class CryptoClient {
	// 正在接收的正文：笔记 ID → 已收到的块。服务端可能不怀好意，块数和同时接收的篇数都要设上限，免得一个包就把内存撑爆
	private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
	// 服务端最多发 2MB 的文件（约 200 万字），每块 10 万字：32 块绰绰有余
	private static final int MAX_CHUNKS = 32;
	private static final int MAX_INCOMING = 4;

	private record Incoming(String title, boolean refresh, boolean editable, String[] parts) {}

	// 这次连接里打开过的加密笔记。服务端之后要是把其中某篇当明文发来（不管是热重载还是重新打开），
	// 一律不当普通笔记默默显示：可能是服务端在"降级"它，骗玩家以为还受保护、往里写秘密。自己解除加密成功后才移除
	// 只记真的用自己的钥匙解开过的，最多 4096 篇。满了就不再记新的，但绝不丢掉旧的：
	// 要是会淘汰，恶意服务端就能先塞一堆密文把目标挤出去，再把它当明文发来
	private static final int MAX_KNOWN_ENCRYPTED = 4096;
	private static final Set<UUID> KNOWN_ENCRYPTED = new LinkedHashSet<>();

	private static void rememberEncrypted(UUID id) {
		if (KNOWN_ENCRYPTED.size() < MAX_KNOWN_ENCRYPTED) {
			KNOWN_ENCRYPTED.add(id);
		}
	}

	// 服务端发来"加密这篇"时也要先确认是玩家自己要的（10 秒内亲手输入过 /palace encrypt 或 /palace newsecret）：
	// 否则恶意服务端能在钥匙解锁时让客户端替任意内容加密，用来批量制造密文
	private static long encryptRequestedAt;
	private static boolean encryptRequested;

	// 解除加密 = 把明文交给服务端。到了别人的服务器上，服务端可能不怀好意：它可以随时发来"解除加密"，
	// 骗玩家输入密码、交出明文，或者把别的笔记的密文换进来。所以只认这台电脑上刚刚在"打开着的那篇"上点的 🔓：
	// 笔记 ID 和密文指纹都要和点按钮时一致，60 秒内有效，用一次就作废
	private static final long DECRYPT_INTENT_NANOS = 60_000_000_000L;

	private record DecryptIntent(UUID noteId, String cipherHash, long atNanos) {}

	private static DecryptIntent decryptIntent;

	// 文件夹独立密码：服务端发来"给这个文件夹设密码"，要是玩家 10 秒内亲手输入过 /palace lockfolder；
	// 设好之后 2 分钟内，服务端逐篇发来的"用这把文件夹钥匙加密这篇"才认（钥匙 ID 记在 folderEncryptLock）
	private static boolean lockFolderRequested;
	private static long lockFolderRequestedAt;
	private static final long FOLDER_ENCRYPT_NANOS = 120_000_000_000L;
	private static String folderEncryptLock;
	private static long folderEncryptAt;
	// 加密文件夹时遇到的旧格式 MPAL1 笔记：要先解锁保险库才能解开再改用文件夹钥匙；保险库还锁着时先排队，解锁一次后一起处理
	private static final java.util.List<CryptoPayloads.Action> WAITING_FOR_VAULT = new java.util.ArrayList<>();

	private CryptoClient() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.KeyringData.TYPE,
				(payload, context) -> ClientKeys.onKeyringData(payload));
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.KeyringWriteResult.TYPE,
				(payload, context) -> ClientKeys.onKeyringWriteResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(CryptoPayloads.Action.TYPE, (payload, context) -> handle(payload));
		ClientPlayNetworking.registerGlobalReceiver(FolderPayloads.LockData.TYPE, (payload, context) -> FolderKeys.onLockData(payload));
		ClientPlayNetworking.registerGlobalReceiver(FolderPayloads.LockWriteResult.TYPE,
				(payload, context) -> FolderKeys.onLockWriteResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(OpenNotePayload.TYPE, (payload, context) -> receive(payload));
		// 命令文本不带开头的 "/"
		ClientSendMessageEvents.COMMAND.register(command -> {
			String c = command.strip();
			if (c.startsWith("palace new ")) {
				newNoteRequested = true;
				newNoteRequestedAt = System.nanoTime();
			} else if (c.startsWith("palace encrypt ") || c.startsWith("palace newsecret ")) {
				encryptRequested = true;
				encryptRequestedAt = System.nanoTime();
			} else if (c.startsWith("palace lockfolder ")) {
				lockFolderRequested = true;
				lockFolderRequestedAt = System.nanoTime();
			}
		});
		// 离开世界一律上锁，丢掉没收完的东西
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			INCOMING.clear();
			KNOWN_ENCRYPTED.clear();
			decryptIntent = null;
			folderEncryptLock = null;
			WAITING_FOR_VAULT.clear();
			InboxCapture.reset();
			ClientKeys.disconnect();
		}));
	}

	// 服务端发来"直接打开编辑器"（/palace new 之后）：只认这台电脑上 10 秒内亲手输入过的 /palace new，
	// 而且绝不对已知的加密笔记打开明文编辑器（否则恶意服务端能把加密笔记换成明文编辑器，骗玩家把秘密以明文保存）
	private static final long NEW_NOTE_INTENT_NANOS = 10_000_000_000L;
	private static long newNoteRequestedAt;
	private static boolean newNoteRequested;

	/** /palace new 之后服务端让客户端直接打开编辑器。 */
	public static void openEditor(EditPayloads.OpenEditor payload) {
		boolean requested = newNoteRequested && System.nanoTime() - newNoteRequestedAt <= NEW_NOTE_INTENT_NANOS;
		newNoteRequested = false;
		if (!requested || KNOWN_ENCRYPTED.contains(payload.noteId()) || Ciphertext.isEncrypted(payload.body())
				|| InboxCipher.hasSealedLines(payload.body())) {
			return;
		}
		Minecraft.getInstance().gui.setScreen(new EditorScreen(payload.noteId(), Component.literal(payload.title()), payload.body()));
	}

	/**
	 * 玩家在打开着的加密笔记上点了 🔓：记下"要解除的是这篇、这份密文"，再请服务端把密文发来。
	 * @param cipherHash 这篇笔记打开时收到的密文的指纹（NoteScreen 里存的 storedHash）
	 */
	public static void requestDecrypt(UUID noteId, String cipherHash) {
		decryptIntent = new DecryptIntent(noteId, cipherHash, System.nanoTime());
		ClientPlayNetworking.send(new CryptoPayloads.DecryptRequest(noteId));
	}

	/** 服务端发来的"解除加密"是不是玩家刚刚亲手要的那一篇、那一份密文？用掉就作废。 */
	private static boolean takeDecryptIntent(UUID noteId, String cipher) {
		DecryptIntent intent = decryptIntent;
		decryptIntent = null;
		if (intent == null) {
			return false;
		}
		long elapsed = System.nanoTime() - intent.atNanos();
		return elapsed >= 0 && elapsed <= DECRYPT_INTENT_NANOS
				&& intent.noteId().equals(noteId) && intent.cipherHash().equals(Vault.hash(cipher));
	}

	/** 收一块正文；收齐了就打开（或刷新）。 */
	private static void receive(OpenNotePayload part) {
		if (part.total() < 1 || part.total() > MAX_CHUNKS || part.index() < 0 || part.index() >= part.total()) {
			return;
		}
		Incoming in = INCOMING.get(part.id());
		boolean sameNote = in != null && in.parts().length == part.total() && in.title().equals(part.title())
				&& in.refresh() == part.refresh() && in.editable() == part.editable();
		if (!sameNote || part.index() == 0) {
			if (!INCOMING.containsKey(part.id()) && INCOMING.size() >= MAX_INCOMING) {
				INCOMING.clear(); // 同时有太多篇没收完：正常不会发生，全部丢掉重来
			}
			in = new Incoming(part.title(), part.refresh(), part.editable(), new String[part.total()]);
			INCOMING.put(part.id(), in);
		}
		in.parts()[part.index()] = part.part();
		for (String p : in.parts()) {
			if (p == null) {
				return;
			}
		}
		INCOMING.remove(part.id());
		openNote(part.id(), Component.literal(part.title()), String.join("", in.parts()), part.refresh(), part.editable());
	}

	/**
	 * 正文收齐了：明文直接打开；密文先解锁再解密。
	 * 收件箱里有随手记密文行（MPALBOX1）的，也当作加密笔记：解锁后用收件箱私钥解开显示；
	 * 在编辑器里整理、保存时整篇存成普通的加密笔记（绝不把解开的内容以明文存回去）。
	 */
	private static void openNote(UUID id, Component title, String stored, boolean refresh, boolean editable) {
		Minecraft mc = Minecraft.getInstance();
		String storedHash = Vault.hash(stored);
		boolean encrypted = Ciphertext.isEncrypted(stored) || InboxCipher.hasSealedLines(stored);
		if (!encrypted && KNOWN_ENCRYPTED.contains(id)) {
			// 这篇刚才还是加密的，现在服务端发来明文：关掉、提醒，不默默当普通笔记打开
			if (mc.gui.screen() instanceof NoteScreen open && id.equals(open.noteId())) {
				mc.gui.setScreen(null);
			}
			PasswordScreen.tell("message.memorypalace.encryption_downgraded");
			return;
		}
		if (refresh) {
			if (!(mc.gui.screen() instanceof NoteScreen open) || !id.equals(open.noteId())) {
				return; // 没开着这篇，不用管
			}
			if (open.isEncrypted() && !encrypted) {
				// 正开着的加密笔记被换成明文：同上，关掉提醒，不重开
				mc.gui.setScreen(null);
				PasswordScreen.tell("message.memorypalace.encryption_downgraded");
			} else if (open.isEncrypted() != encrypted) {
				// 普通笔记在外部被加密了：界面状态对不上，不能原地刷新，关掉重开（重开时会要求解锁）
				mc.gui.setScreen(null);
				openNote(id, title, stored, false, editable);
			} else if (!encrypted) {
				open.updateBody(stored, storedHash);
			} else if (canDecryptNow(stored)) {
				decrypt(id, stored).ifPresent(plain -> open.updateBody(plain, storedHash));
			}
			return;
		}
		if (!encrypted) {
			mc.gui.setScreen(new NoteScreen(id, title, stored, false, storedHash, editable));
			return;
		}
		unlockFor(id, stored, () -> decrypt(id, stored).ifPresentOrElse(
				plain -> {
					// 真的用自己的钥匙解开了，才算"已知的加密笔记"。收件箱随手记不算：别人有公钥也能往里加密文行
					if (Ciphertext.isEncrypted(stored)) {
						rememberEncrypted(id);
					}
					mc.gui.setScreen(new NoteScreen(id, title, plain, true, storedHash, editable));
				},
				() -> mc.gui.setScreen(null)));
	}

	/** 这篇需要的钥匙：MPAL2 文件夹笔记要文件夹钥匙（输文件夹密码），其余要保险库主密钥。 */
	private static void unlockFor(UUID id, String stored, Runnable then) {
		String folderLock = NoteEnvelope.isEnvelope(stored) ? folderLockOf(stored) : null;
		if (folderLock != null) {
			FolderKeys.require(id, folderLock, null, then);
		} else if (NoteEnvelope.isEnvelope(stored)) {
			PasswordScreen.tell("message.memorypalace.decrypt_failed"); // 不认识的锁（以后的单篇密码等）
		} else {
			ClientKeys.requireUnlocked(null, then);
		}
	}

	/** 现在手里的钥匙能不能直接解开（热重载时用，不弹密码框）。 */
	private static boolean canDecryptNow(String stored) {
		if (NoteEnvelope.isEnvelope(stored)) {
			String lock = folderLockOf(stored);
			return lock != null && FolderKeys.get(lock) != null;
		}
		return ClientKeys.unlocked();
	}

	/** MPAL2 笔记里文件夹锁的 ID；没有文件夹锁或格式不对时为 null。 */
	private static String folderLockOf(String envelope) {
		try {
			return NoteEnvelope.locks(envelope).stream().filter(l -> l.kind() == NoteEnvelope.Kind.FOLDER)
					.map(NoteEnvelope.Lock::id).findFirst().orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static Optional<String> decrypt(UUID noteId, String stored) {
		if (NoteEnvelope.isEnvelope(stored)) {
			// 文件夹笔记：用文件夹钥匙取出这篇的内容钥匙，记下来（保存时用它再加密）
			String lock = folderLockOf(stored);
			MasterKey folderKey = lock == null ? null : FolderKeys.get(lock);
			if (folderKey == null) {
				PasswordScreen.tell("message.memorypalace.decrypt_failed");
				return Optional.empty();
			}
			byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
			try {
				ContentKey contentKey = NoteEnvelope.unlock(noteId, stored, new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lock), wrapping);
				String plain = NoteEnvelope.decrypt(noteId, stored, contentKey);
				NoteSealers.remember(noteId, contentKey, stored);
				return Optional.of(plain);
			} catch (WrongKeyException e) {
				PasswordScreen.tell("message.memorypalace.decrypt_failed");
				return Optional.empty();
			} finally {
				java.util.Arrays.fill(wrapping, (byte) 0);
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

	private static void handle(CryptoPayloads.Action action) {
		Minecraft mc = Minecraft.getInstance();
		UUID id = action.noteId();
		Component title = Component.literal(action.title());
		String body = action.body();
		switch (action.action()) {
			case "lock" -> ClientKeys.lock(true);
			case "lock_folder" -> {
				boolean requested = lockFolderRequested && System.nanoTime() - lockFolderRequestedAt <= NEW_NOTE_INTENT_NANOS;
				lockFolderRequested = false;
				if (requested) {
					lockFolder(action.title());
				}
			}
			case "encrypt_folder" -> encryptFolderNote(action);
			case "encrypt_new_folder" -> {
				boolean requested = newNoteRequested && System.nanoTime() - newNoteRequestedAt <= NEW_NOTE_INTENT_NANOS;
				newNoteRequested = false;
				if (requested) {
					newFolderNote(id, title, body);
				}
			}
			case "encrypt", "encrypt_new" -> {
				boolean requested = encryptRequested && System.nanoTime() - encryptRequestedAt <= NEW_NOTE_INTENT_NANOS;
				encryptRequested = false;
				if (!requested) {
					return;
				}
				encrypt(action, id, title, body);
			}
			// 解除加密 = 把明文永久写回硬盘，所以即使已经解锁也要重新输一次密码
			case "decrypt" -> {
				if (!takeDecryptIntent(id, body)) {
					PasswordScreen.tell("message.memorypalace.decrypt_unrequested");
					return;
				}
				if (NoteEnvelope.isEnvelope(body)) {
					// 文件夹里的笔记不能单独解除加密（服务端也不让在被锁的文件夹里存明文）；要整个文件夹解除密码
					PasswordScreen.tell("message.memorypalace.folder_note_decrypt");
					return;
				}
				ClientKeys.withKeyring(keyring -> {
					if (keyring.isEmpty()) {
						PasswordScreen.tell("message.memorypalace.no_keyring");
						return;
					}
					mc.gui.setScreen(PasswordScreen.verify(keyring, PasswordScreen.t("verify_decrypt", action.title()), null,
							(password, key) -> decryptAndSave(id, action.title(), body, key)));
				});
			}
			default -> {}
		}
	}

	// ---- 文件夹独立密码 ----

	/** /palace lockfolder：设文件夹密码 → 锁文件存好 → 请服务端把文件夹里的笔记逐篇发来加密。 */
	private static void lockFolder(String folder) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(PasswordScreen.setupFolder(folder, null, created -> {
			String lockId = KeyRing.folderId(created.content());
			FolderKeys.put(lockId, created.key());
			folderEncryptLock = lockId;
			folderEncryptAt = System.nanoTime();
			PasswordScreen.tell("message.memorypalace.folder_locked");
			mc.gui.setScreen(null);
			ClientPlayNetworking.send(new FolderPayloads.EncryptAll(folder));
		}));
	}

	/** 服务端发来文件夹里的一篇（明文或旧格式 MPAL1）：用刚设好的文件夹钥匙重新加密后存回去。 */
	private static void encryptFolderNote(CryptoPayloads.Action action) {
		String lockId = folderEncryptLock;
		if (lockId == null || System.nanoTime() - folderEncryptAt > FOLDER_ENCRYPT_NANOS) {
			return; // 不是刚锁好文件夹时发来的：不认
		}
		MasterKey folderKey = FolderKeys.get(lockId);
		if (folderKey == null) {
			PasswordScreen.tell("message.memorypalace.folder_encrypt_failed");
			return;
		}
		String body = action.body();
		if (NoteEnvelope.isEnvelope(body)) {
			return; // 已经是新格式了（比如又被锁在了别的文件夹里）：不动
		}
		if (NoteCipher.isEncrypted(body)) {
			// 旧格式加密笔记：要先用保险库主密钥解开。保险库还锁着时排队，解锁一次后一起处理
			if (!ClientKeys.unlocked()) {
				WAITING_FOR_VAULT.add(action);
				if (WAITING_FOR_VAULT.size() == 1) {
					ClientKeys.requireUnlocked(null, () -> {
						Minecraft.getInstance().gui.setScreen(null);
						List<CryptoPayloads.Action> queued = List.copyOf(WAITING_FOR_VAULT);
						WAITING_FOR_VAULT.clear();
						folderEncryptAt = System.nanoTime(); // 输密码花的时间不算
						queued.forEach(CryptoClient::encryptFolderNote);
					});
				}
				return;
			}
			try {
				saveWithFolderLock(action.noteId(), NoteCipher.decrypt(ClientKeys.key(), action.noteId(), body), body, lockId, folderKey, null);
			} catch (WrongKeyException e) {
				PasswordScreen.tell("message.memorypalace.folder_encrypt_failed");
			}
			return;
		}
		saveWithFolderLock(action.noteId(), body, body, lockId, folderKey, null);
	}

	/** 在被锁的文件夹里新建的笔记：解锁文件夹（需要时输文件夹密码）→ 用文件夹钥匙加密 → 存好后打开编辑器。 */
	private static void newFolderNote(UUID id, Component title, String body) {
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

	/**
	 * 用文件夹钥匙给这篇加密并存回去：新的内容钥匙，只有一把 f 锁（保险库密码打不开）。
	 * stored 是服务端那边现在的正文（用来核对存的时候没有被别人改过）。存好后记下内容钥匙，之后编辑保存用。
	 */
	private static void saveWithFolderLock(UUID id, String plain, String stored, String lockId, MasterKey folderKey,
			java.util.function.Consumer<EditPayloads.SaveResult> after) {
		ContentKey contentKey = ContentKey.random();
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
		String sealed;
		try {
			sealed = NoteEnvelope.seal(id, contentKey, plain,
					java.util.Map.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId), wrapping));
		} finally {
			java.util.Arrays.fill(wrapping, (byte) 0);
		}
		NoteSaver.save(id, sealed, Vault.hash(stored), result -> {
			if ("OK".equals(result.result())) {
				rememberEncrypted(id);
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

	private static void encrypt(CryptoPayloads.Action action, UUID id, Component title, String body) {
		Minecraft mc = Minecraft.getInstance();
		ClientKeys.requireUnlocked(null, () -> {
			String cipher = NoteCipher.encrypt(ClientKeys.key(), id, body);
			NoteSaver.save(id, cipher, Vault.hash(body), result -> {
				if (!"OK".equals(result.result())) {
					PasswordScreen.tell("message.memorypalace.crypto_failed");
					mc.gui.setScreen(null);
					return;
				}
				rememberEncrypted(id); // 自己加密的，自然知道它是加密笔记
				PasswordScreen.tell("message.memorypalace.encrypted");
				mc.gui.setScreen("encrypt_new".equals(action.action()) && ClientKeys.unlocked()
						? new EditorScreen(id, title, body, true, result.newHash())
						: null);
			});
		});
	}

	private static void decryptAndSave(UUID id, String title, String cipher, MasterKey key) {
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
					KNOWN_ENCRYPTED.remove(id); // 是自己解除的：以后它以明文出现是正常的
				}
				PasswordScreen.tell(ok ? "message.memorypalace.decrypted" : "message.memorypalace.crypto_failed");
				mc.gui.setScreen(null);
			});
		}, Component.translatable("screen.memorypalace.confirm_decrypt.title", title),
				Component.translatable("screen.memorypalace.confirm_decrypt.message", preview(plain)),
				Component.translatable("screen.memorypalace.confirm_decrypt.yes"),
				Component.translatable("screen.memorypalace.confirm_decrypt.no")));
	}

	/** 正文开头一小段（换行变成空格），给确认框用。 */
	private static String preview(String plain) {
		String flat = plain.strip().replaceAll("\\s+", " ");
		return flat.length() <= 80 ? flat : flat.substring(0, 79) + "…";
	}
}
