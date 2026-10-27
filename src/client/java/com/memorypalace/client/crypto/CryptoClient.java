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
import com.memorypalace.mc.PalaceInteractions;
import com.memorypalace.mc.SharePayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

	// 文件夹独立密码：服务端发来"给这个文件夹设密码 / 继续加密"，要是玩家 10 秒内亲手输入过 /palace lockfolder。
	// 之后服务端逐篇发来的"用这把文件夹钥匙加密 / 解密这篇"，只在 FolderBatch 正在做这把锁的那一批时才认
	private static boolean lockFolderRequested;
	private static long lockFolderRequestedAt;
	// /palace folderpassword、/palace unlockfolder：10 秒内亲手输入过才认服务端发来的对应请求
	private static String folderManageRequested;
	private static long folderManageRequestedAt;
	// /palace sharepassword、/palace unsharepassword：同上
	private static String readPasswordRequested;
	private static long readPasswordRequestedAt;

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
		ClientPlayNetworking.registerGlobalReceiver(SharePayloads.PasswordPrompt.TYPE, (payload, context) -> ReadPasswords.onPrompt(payload));
		ClientPlayNetworking.registerGlobalReceiver(SharePayloads.PasswordResult.TYPE, (payload, context) -> ReadPasswords.onResult(payload));
		// 整个文件夹加密 / 解密时逐篇保存：每刻看看能不能发下一篇（服务端对保存限速）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			FolderBatch.tick();
			ReadPasswords.tick(); // 阅读密码核对 30 秒没下文就作废
		});
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
			} else if (c.startsWith("palace folderpassword ") || c.startsWith("palace unlockfolder ")) {
				folderManageRequested = c.startsWith("palace folderpassword ") ? "folder_password" : "unlock_folder";
				folderManageRequestedAt = System.nanoTime();
			} else if (c.startsWith("palace sharepassword ") || c.startsWith("palace unsharepassword ")) {
				readPasswordRequested = c.startsWith("palace sharepassword ") ? "set_read_password" : "remove_read_password";
				readPasswordRequestedAt = System.nanoTime();
			}
		});
		// 进入世界：不认进来之前的右键（阅读密码弹框只认这次连接里亲手右键过的书）
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(PalaceInteractions::clearLocalOpen));
		// 离开世界一律上锁，丢掉没收完的东西
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			INCOMING.clear();
			KNOWN_ENCRYPTED.clear();
			decryptIntent = null;
			FolderBatch.reset();
			WAITING_FOR_VAULT.clear();
			InboxCapture.reset();
			ClientKeys.disconnect();
			ReadPasswords.disconnect();
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
	private static void openNote(UUID id, Component title, String stored, boolean refresh, boolean serverEditable) {
		Minecraft mc = Minecraft.getInstance();
		// 这次连接里接受过阅读密码题目的别人的笔记：只读，只认能用阅读密码钥匙解开的密文。
		// 恶意服务端不能发一份明文（或别的锁）冒充，骗玩家以为在看受保护的笔记、往里写东西
		boolean readerNote = ReadPasswords.isReaderNote(id);
		if (readerNote && !ReadPasswords.matches(id, stored)) {
			if (mc.gui.screen() instanceof NoteScreen open && id.equals(open.noteId())) {
				mc.gui.setScreen(null);
			}
			if (!refresh) {
				PasswordScreen.tell(Ciphertext.isEncrypted(stored)
						? "message.memorypalace.read_password_again" : "message.memorypalace.encryption_downgraded");
			}
			return;
		}
		boolean editable = serverEditable && !readerNote;
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
			} else if (canDecryptNow(id, stored)) {
				decrypt(id, stored).ifPresent(plain -> open.updateBody(plain, storedHash));
			}
			return;
		}
		if (!encrypted) {
			mc.gui.setScreen(new NoteScreen(id, title, stored, false, storedHash, editable));
			return;
		}
		unlockFor(id, stored, editable, () -> decrypt(id, stored).ifPresentOrElse(
				plain -> {
					// 真的用自己的钥匙解开了，才算"已知的加密笔记"。收件箱随手记不算：别人有公钥也能往里加密文行
					if (Ciphertext.isEncrypted(stored)) {
						rememberEncrypted(id);
					}
					mc.gui.setScreen(new NoteScreen(id, title, plain, true, storedHash, editable));
				},
				() -> mc.gui.setScreen(null)));
	}

	/**
	 * 这篇需要的钥匙。MPAL2：刚用阅读密码核对通过的（别人的笔记）直接解；文件夹笔记要文件夹钥匙（输文件夹密码）；
	 * 有自己 u 锁的（设过阅读密码的笔记）要保险库主密钥。旧格式 MPAL1 要保险库主密钥。
	 * editable = false 又有阅读密码锁的是别人的笔记：钥匙已经清掉了（上锁了），不能拿自己的保险库密码去试，让玩家重新打开。
	 */
	private static void unlockFor(UUID id, String stored, boolean editable, Runnable then) {
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
	private static boolean canDecryptNow(UUID id, String stored) {
		if (NoteEnvelope.isEnvelope(stored)) {
			String lock = folderLockOf(stored);
			return ReadPasswords.matches(id, stored) || lock != null && FolderKeys.get(lock) != null
					|| hasUserLock(stored) && ClientKeys.unlocked();
		}
		return ClientKeys.unlocked();
	}

	private static boolean hasUserLock(String envelope) {
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
	private static String selfId() {
		var player = Minecraft.getInstance().player;
		return player == null ? "host" : player.getUUID().toString();
	}

	/**
	 * 用手里的钥匙取出 MPAL2 笔记的内容钥匙：核对通过的阅读密码 → 文件夹钥匙 → 自己的保险库主密钥。
	 * 都没有 → null；有钥匙但打不开 → WrongKeyException。
	 */
	private static ContentKey envelopeKey(UUID id, String stored) throws WrongKeyException {
		byte[] readKey = ReadPasswords.wrappingKeyFor(id, stored);
		if (readKey != null) {
			// （用阅读密码打开的一律只读，见 openNote 里的 takeAwaiting）
			try {
				return NoteEnvelope.unlock(id, stored, NoteEnvelope.passwordLock(stored).orElseThrow(), readKey);
			} finally {
				java.util.Arrays.fill(readKey, (byte) 0);
			}
		}
		String lock = folderLockOf(stored);
		MasterKey folderKey = lock == null ? null : FolderKeys.get(lock);
		if (folderKey != null) {
			byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
			try {
				return NoteEnvelope.unlock(id, stored, new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lock), wrapping);
			} finally {
				java.util.Arrays.fill(wrapping, (byte) 0);
			}
		}
		if (hasUserLock(stored) && ClientKeys.unlocked()) {
			return NoteEnvelope.unlockAsUser(id, stored, ClientKeys.key(), selfId());
		}
		return null;
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
			} catch (WrongKeyException | IllegalArgumentException | java.util.NoSuchElementException e) {
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

	private static void handle(CryptoPayloads.Action action) {
		Minecraft mc = Minecraft.getInstance();
		UUID id = action.noteId();
		Component title = Component.literal(action.title());
		String body = action.body();
		switch (action.action()) {
			case "lock" -> ClientKeys.lock(true);
			case "lock_folder", "resume_folder" -> {
				boolean requested = lockFolderRequested && System.nanoTime() - lockFolderRequestedAt <= NEW_NOTE_INTENT_NANOS;
				lockFolderRequested = false;
				if (!requested) {
					return;
				}
				if (action.action().equals("lock_folder")) {
					lockFolder(action.title());
				} else {
					resumeFolder(action.title(), action.body());
				}
			}
			case "encrypt_folder" -> encryptFolderNote(action);
			case "encrypt_folder_done" -> FolderBatch.serverDone(FolderBatch.Kind.ENCRYPT, action.title(), parseCount(body));
			case "folder_password", "unlock_folder" -> {
				boolean requested = action.action().equals(folderManageRequested)
						&& System.nanoTime() - folderManageRequestedAt <= NEW_NOTE_INTENT_NANOS;
				folderManageRequested = null;
				if (!requested) {
					return;
				}
				if (action.action().equals("folder_password")) {
					changeFolderPassword(action.title(), action.body());
				} else {
					unlockFolder(action.title(), action.body());
				}
			}
			case "set_read_password", "remove_read_password" -> {
				boolean requested = action.action().equals(readPasswordRequested)
						&& System.nanoTime() - readPasswordRequestedAt <= NEW_NOTE_INTENT_NANOS;
				readPasswordRequested = null;
				if (!requested) {
					return;
				}
				changeReadPassword(id, action.title(), body, action.action().equals("set_read_password"));
			}
			case "decrypt_folder" -> decryptFolderNote(action);
			case "decrypt_folder_done" -> FolderBatch.serverDone(FolderBatch.Kind.DECRYPT, action.title(), parseCount(body));
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
				if (NoteEnvelope.isEnvelope(body) && folderLockOf(body) != null) {
					// 文件夹里的笔记不能单独解除加密（服务端也不让在被锁的文件夹里存明文）；要整个文件夹解除密码
					PasswordScreen.tell("message.memorypalace.folder_note_decrypt");
					return;
				}
				if (NoteEnvelope.isEnvelope(body) && !hasUserLock(body)) {
					PasswordScreen.tell("message.memorypalace.decrypt_failed");
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

	// ---- 单篇阅读密码 ----

	/**
	 * /palace sharepassword（set）、/palace unsharepassword：解开这篇 → （设密码时）输两遍新阅读密码 →
	 * 换一把新的内容钥匙重新加密（自己的锁 + 新的 p 锁，或者只有自己的锁）→ 存回去。
	 * 换内容钥匙是为了真正收回：以前用旧密码看过的人手里的内容钥匙，对新密文没用。
	 * 旧格式 MPAL1 的笔记这时改成新格式（只这一篇，是玩家自己要的）。
	 */
	private static void changeReadPassword(UUID id, String title, String stored, boolean set) {
		if (!Ciphertext.isEncrypted(stored)) {
			PasswordScreen.tell("message.memorypalace.crypto_failed");
			return;
		}
		withPlain(id, stored, plain -> {
			if (!set) {
				resealAndSave(id, stored, plain, null, "message.memorypalace.read_password_removed");
				return;
			}
			Minecraft.getInstance().gui.setScreen(PasswordScreen.newReadPassword(title, preview(plain),
					lock -> resealAndSave(id, stored, plain, lock, "message.memorypalace.read_password_set")));
		});
	}

	/** 用自己的钥匙解开这篇（需要时先输保险库密码或文件夹密码），在渲染线程把明文交给 then。 */
	private static void withPlain(UUID id, String stored, java.util.function.Consumer<String> then) {
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
			} catch (WrongKeyException | IllegalArgumentException | java.util.NoSuchElementException e) {
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

	/**
	 * 作者自己能重新包上的锁：文件夹笔记 → 原来那把 f 锁；其余 → 自己的 u 锁。钥匙已经不在手里（中途上锁了）→ null。
	 * 返回的包装钥匙由调用方清零。
	 */
	private static java.util.Map<NoteEnvelope.Lock, byte[]> ownLocks(String stored) {
		java.util.Map<NoteEnvelope.Lock, byte[]> locks = new java.util.LinkedHashMap<>();
		String folderLock = NoteEnvelope.isEnvelope(stored) ? folderLockOf(stored) : null;
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
			locks.put(NoteEnvelope.Lock.user(selfId()), NoteEnvelope.userWrappingKey(ClientKeys.key()));
		}
		return locks;
	}

	/** 新内容钥匙 + 自己的锁（+ 阅读密码锁）重新加密，存回去；存好才记下新的内容钥匙。 */
	private static void resealAndSave(UUID id, String stored, String plain, NoteEnvelope.PasswordLock passwordLock, String doneKey) {
		Minecraft mc = Minecraft.getInstance();
		java.util.Map<NoteEnvelope.Lock, byte[]> locks = ownLocks(stored);
		if (locks == null) {
			if (passwordLock != null) {
				java.util.Arrays.fill(passwordLock.wrappingKey(), (byte) 0);
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
			locks.values().forEach(k -> java.util.Arrays.fill(k, (byte) 0));
		}
		int generation = ClientKeys.generation();
		NoteSealers.pending(contentKey); // 服务端一直不回应时，上锁照样把它清零
		NoteSaver.save(id, sealed, Vault.hash(stored), result -> {
			NoteSealers.settled(contentKey);
			if (generation != ClientKeys.generation()) {
				// 回包之前已经上锁 / 离开过世界：新内容钥匙不能在上锁之后又留进内存
				contentKey.destroy();
				if ("OK".equals(result.result())) {
					rememberEncrypted(id);
					PasswordScreen.tell(doneKey);
				}
				return;
			}
			if ("OK".equals(result.result())) {
				rememberEncrypted(id);
				NoteSealers.remember(id, contentKey, sealed);
				PasswordScreen.tell(doneKey);
			} else {
				contentKey.destroy();
				PasswordScreen.tell("message.memorypalace.crypto_failed");
			}
			mc.gui.setScreen(null);
		});
	}

	// ---- 文件夹独立密码 ----

	/** /palace lockfolder：设文件夹密码 → 锁文件存好 → 请服务端把文件夹里的笔记逐篇发来加密。 */
	private static void lockFolder(String folder) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(PasswordScreen.setupFolder(folder, null, created -> {
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
	private static void resumeFolder(String folder, String lock) {
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
		java.util.function.Consumer<MasterKey> go = key -> {
			FolderKeys.put(lockId, key);
			mc.gui.setScreen(null);
			startEncrypting(folder, lockId, "message.memorypalace.folder_resuming");
		};
		mc.gui.setScreen(PasswordScreen.unlockFolder(folder, lock, null, go));
	}

	private static void startEncrypting(String folder, String lockId, String messageKey) {
		FolderBatch.start(FolderBatch.Kind.ENCRYPT, folder, lockId, null);
		PasswordScreen.tell(messageKey);
		ClientPlayNetworking.send(new FolderPayloads.EncryptAll(folder));
	}

	private static int parseCount(String text) {
		try {
			return Math.max(0, Integer.parseInt(text.strip()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** 服务端发来文件夹里的一篇（明文、旧格式 MPAL1、或设过阅读密码的新格式）：用这把文件夹钥匙重新加密，排进队列逐篇存回去。 */
	private static void encryptFolderNote(CryptoPayloads.Action action) {
		String lockId = FolderBatch.lockId();
		if (lockId == null || !FolderBatch.active(FolderBatch.Kind.ENCRYPT, lockId)) {
			return; // 不是正在加密的那一批（玩家没刚锁好 / 没要求继续）：不认
		}
		MasterKey folderKey = FolderKeys.get(lockId);
		String body = action.body();
		boolean ownEnvelope = NoteEnvelope.isEnvelope(body) && folderLockOf(body) == null && hasUserLock(body);
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
		ContentKey contentKey = NoteEnvelope.unlockAsUser(id, body, ClientKeys.key(), selfId());
		try {
			return NoteEnvelope.decrypt(id, body, contentKey);
		} finally {
			contentKey.destroy();
		}
	}

	/** 用文件夹钥匙加密好，排进 FolderBatch 逐篇保存（存成功才记下内容钥匙）。 */
	private static void queueFolderSeal(UUID id, String plain, String stored, String lockId, MasterKey folderKey) {
		Sealed sealed = sealWithFolderLock(id, plain, lockId, folderKey);
		FolderBatch.add(new FolderBatch.SaveJob(id, sealed.text(), Vault.hash(stored), ok -> {
			if (ok) {
				rememberEncrypted(id);
				NoteSealers.remember(id, sealed.key(), sealed.text());
			} else {
				sealed.key().destroy();
			}
		}));
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

	/** /palace folderpassword：输当前文件夹密码（忘了就用恢复密钥）→ 设新密码 → 锁存好。 */
	private static void changeFolderPassword(String folder, String lock) {
		Minecraft mc = Minecraft.getInstance();
		String lockId = folderIdOf(lock);
		if (lockId == null) {
			PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
			return;
		}
		java.util.function.Consumer<MasterKey> done = key -> {
			FolderKeys.put(lockId, key);
			PasswordScreen.tell("message.memorypalace.folder_password_changed");
			mc.gui.setScreen(null);
		};
		mc.gui.setScreen(PasswordScreen.verifyFolder(folder, lock, PasswordScreen.t("verify_folder_password"), null,
				key -> mc.gui.setScreen(PasswordScreen.newFolderPassword(folder, lock, key, null, done)),
				done)); // 用恢复密钥重设时已经设过新密码了
	}

	/**
	 * /palace unlockfolder：输文件夹密码（忘了就用恢复密钥）→ 确认"里面的笔记会以明文存回服务器" →
	 * 请服务端把还用着这把锁的笔记逐篇发来解密 → 全部存好后删锁文件。
	 */
	private static void unlockFolder(String folder, String lock) {
		Minecraft mc = Minecraft.getInstance();
		String lockId = folderIdOf(lock);
		if (lockId == null) {
			PasswordScreen.tell("message.memorypalace.folder_lock_unavailable");
			return;
		}
		java.util.function.Consumer<MasterKey> confirm = key -> mc.gui.setScreen(new ConfirmScreen(yes -> {
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
		mc.gui.setScreen(PasswordScreen.verifyFolder(folder, lock, PasswordScreen.t("verify_unlock_folder"), null, confirm, confirm));
	}

	/**
	 * 解除文件夹密码时服务端发来的一篇：用这把文件夹钥匙解开，排进 FolderBatch 逐篇存回明文。
	 * 全部存好、服务端也说发完了，FolderBatch 才删锁；有失败就不删（锁还在，没解开的笔记照样能用文件夹密码打开）。
	 */
	private static void decryptFolderNote(CryptoPayloads.Action action) {
		String lockId = FolderBatch.lockId();
		UUID id = action.noteId();
		String body = action.body();
		// 只认正在解除的那一批，只解这把锁的笔记：服务端不能借机让客户端解开别的文件夹（或者别的锁）的笔记
		if (lockId == null || !FolderBatch.active(FolderBatch.Kind.DECRYPT, lockId)
				|| !NoteEnvelope.isEnvelope(body) || !lockId.equals(folderLockOf(body))) {
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
			java.util.Arrays.fill(wrapping, (byte) 0);
		}
		FolderBatch.add(new FolderBatch.SaveJob(id, plain, Vault.hash(body), ok -> {
			if (ok) {
				KNOWN_ENCRYPTED.remove(id); // 是自己解除的：以后它以明文出现是正常的
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

	/** 用文件夹钥匙加密好的一篇：新的内容钥匙 + 密文。 */
	private record Sealed(ContentKey key, String text) {}

	/** 用文件夹钥匙给这篇加密：新的内容钥匙，只有一把 f 锁（保险库密码打不开）。 */
	private static Sealed sealWithFolderLock(UUID id, String plain, String lockId, MasterKey folderKey) {
		ContentKey contentKey = ContentKey.random();
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
		try {
			return new Sealed(contentKey, NoteEnvelope.seal(id, contentKey, plain,
					java.util.Map.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId), wrapping)));
		} finally {
			java.util.Arrays.fill(wrapping, (byte) 0);
		}
	}

	/**
	 * 加密单独一篇并马上存回去（在被锁的文件夹里新建笔记时）。stored 是服务端那边现在的正文（用来核对没被别人改过）。
	 * 存好后记下内容钥匙，之后编辑保存用。
	 */
	private static void saveWithFolderLock(UUID id, String plain, String stored, String lockId, MasterKey folderKey,
			java.util.function.Consumer<EditPayloads.SaveResult> after) {
		Sealed s = sealWithFolderLock(id, plain, lockId, folderKey);
		ContentKey contentKey = s.key();
		String sealed = s.text();
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
			if (NoteEnvelope.isEnvelope(cipher)) {
				// 设过阅读密码的笔记（新格式、有自己的 u 锁）：解除加密后阅读密码也就没了
				ContentKey contentKey = NoteEnvelope.unlockAsUser(id, cipher, key, selfId());
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
