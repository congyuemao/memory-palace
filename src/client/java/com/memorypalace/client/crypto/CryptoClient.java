package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.client.NoteScreen;
import com.memorypalace.core.crypto.Ciphertext;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.vault.FolderCopy;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoAction;
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
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端处理笔记正文：收齐分块、打开时（加密笔记先解锁再解密）、热重载时解密，
 * 以及把服务端转来的"加密 / 解除加密 / 上锁"等动作核对意图后分派给各个流程（PlainCrypto、FolderCrypto、CopyCrypto、HistoryCrypto、ReadPasswordCrypto）。
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

	static void rememberEncrypted(UUID id) {
		if (KNOWN_ENCRYPTED.size() < MAX_KNOWN_ENCRYPTED) {
			KNOWN_ENCRYPTED.add(id);
		}
	}

	static boolean isKnownEncrypted(UUID id) {
		return KNOWN_ENCRYPTED.contains(id);
	}

	/** 自己解除了加密：以后它以明文出现是正常的。 */
	static void forgetEncrypted(UUID id) {
		KNOWN_ENCRYPTED.remove(id);
	}

	// 服务端发来的打开编辑器、加密、设文件夹密码、设阅读密码等请求，要先确认是玩家自己要的（见 CommandIntents）：
	// 否则恶意服务端能在钥匙解锁时让客户端替任意内容加密，或者随时弹框骗玩家输密码

	// 解除加密 = 把明文交给服务端。到了别人的服务器上，服务端可能不怀好意：它可以随时发来"解除加密"，
	// 骗玩家输入密码、交出明文，或者把别的笔记的密文换进来。所以只认这台电脑上刚刚在"打开着的那篇"上点的 🔓：
	// 笔记 ID 和密文指纹都要和点按钮时一致，60 秒内有效，用一次就作废
	private static final long DECRYPT_INTENT_NANOS = 60_000_000_000L;

	private record DecryptIntent(UUID noteId, String cipherHash, long atNanos) {}

	private static DecryptIntent decryptIntent;

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
		ClientSendMessageEvents.COMMAND.register(CommandIntents::onCommand);
		// 进入世界：不认进来之前的右键（阅读密码弹框只认这次连接里亲手右键过的书）
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(PalaceInteractions::clearLocalOpen));
		// 离开世界一律上锁，丢掉没收完的东西
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			INCOMING.clear();
			KNOWN_ENCRYPTED.clear();
			CommandIntents.clear();
			decryptIntent = null;
			FolderBatch.reset();
			FolderCrypto.disconnect();
			InboxCapture.disconnect();
			ClientKeys.disconnect();
			ReadPasswords.disconnect();
		}));
	}

	// 服务端发来"直接打开编辑器"（/palace new 之后）：只认这台电脑上 10 秒内亲手输入过的 /palace new，
	// 而且绝不对已知的加密笔记打开明文编辑器（否则恶意服务端能把加密笔记换成明文编辑器，骗玩家把秘密以明文保存）
	private static final long NEW_NOTE_INTENT_NANOS = CommandIntents.WINDOW_NANOS;

	/** /palace new 之后服务端让客户端直接打开编辑器。 */
	public static void openEditor(EditPayloads.OpenEditor payload) {
		// 或者刚右键了"普通"空白记忆笔记（右键上锁笔记之后绝不认：那样秘密会以明文存回去）
		boolean item = PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.PLAIN_BLANK), null,
				NEW_NOTE_INTENT_NANOS);
		boolean requested = CommandIntents.take(CommandIntents.Group.NEW_NOTE, CommandIntents.Kind.NEW_NOTE) || item;
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
			} else if (NoteUnlock.canDecryptNow(id, stored)) {
				NoteUnlock.decrypt(id, stored).ifPresent(plain -> open.updateBody(plain, storedHash));
			}
			return;
		}
		if (!encrypted) {
			mc.gui.setScreen(new NoteScreen(id, title, stored, false, storedHash, editable));
			return;
		}
		NoteUnlock.unlockFor(id, stored, editable, () -> NoteUnlock.decrypt(id, stored).ifPresentOrElse(
				plain -> {
					// 真的用自己的钥匙解开了，才算"已知的加密笔记"。收件箱随手记不算：别人有公钥也能往里加密文行
					if (Ciphertext.isEncrypted(stored)) {
						rememberEncrypted(id);
					}
					mc.gui.setScreen(new NoteScreen(id, title, plain, true, storedHash, editable));
				},
				() -> mc.gui.setScreen(null)));
	}

	private static void handle(CryptoPayloads.Action action) {
		UUID id = action.noteId();
		Component title = Component.literal(action.title());
		String body = action.body();
		CryptoAction kind = CryptoAction.fromWire(action.action());
		if (kind == null) {
			return; // 认不出的动作（别的版本的服务端）：不理
		}
		switch (kind) {
			case LOCK -> ClientKeys.lock(true);
			case LOCK_FOLDER, RESUME_FOLDER -> {
				if (!CommandIntents.take(CommandIntents.Group.LOCK_FOLDER, CommandIntents.Kind.LOCK_FOLDER)) {
					return;
				}
				if (kind == CryptoAction.LOCK_FOLDER) {
					FolderCrypto.lockFolder(action.title());
				} else {
					FolderCrypto.resumeFolder(action.title(), action.body());
				}
			}
			case ENCRYPT_FOLDER -> FolderCrypto.encryptFolderNote(action);
			case ENCRYPT_FOLDER_DONE -> FolderBatch.serverDone(FolderBatch.Kind.ENCRYPT, action.title(), parseCount(body));
			case FOLDER_PASSWORD, UNLOCK_FOLDER -> {
				CommandIntents.Kind wanted = kind == CryptoAction.FOLDER_PASSWORD
						? CommandIntents.Kind.FOLDER_PASSWORD : CommandIntents.Kind.UNLOCK_FOLDER;
				if (!CommandIntents.take(CommandIntents.Group.FOLDER_MANAGE, wanted)) {
					return;
				}
				if (kind == CryptoAction.FOLDER_PASSWORD) {
					FolderCrypto.changeFolderPassword(action.title(), action.body());
				} else {
					FolderCrypto.unlockFolder(action.title(), action.body());
				}
			}
			case SET_READ_PASSWORD, REMOVE_READ_PASSWORD -> {
				CommandIntents.Kind wanted = kind == CryptoAction.SET_READ_PASSWORD
						? CommandIntents.Kind.SET_READ_PASSWORD : CommandIntents.Kind.REMOVE_READ_PASSWORD;
				if (!CommandIntents.take(CommandIntents.Group.READ_PASSWORD, wanted)) {
					return;
				}
				ReadPasswordCrypto.changeReadPassword(id, action.title(), body, kind == CryptoAction.SET_READ_PASSWORD);
			}
			case VIEW_VERSION -> HistoryCrypto.viewVersion(id, action.title(), body);
			case RESTORE_VERSION -> HistoryCrypto.restoreVersion(id, action.title(), body);
			case DECRYPT_FOLDER -> FolderCrypto.decryptFolderNote(action);
			case DECRYPT_FOLDER_DONE -> FolderBatch.serverDone(FolderBatch.Kind.DECRYPT, action.title(), parseCount(body));
			case COPY_ENCRYPTED -> {
				// 刚右键了抄这一篇的空白抄本：用自己的钥匙解开原笔记，按新笔记重新加密存进去
				if (PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.COPY), CopyCrypto.copySource(body),
						NEW_NOTE_INTENT_NANOS)) {
					CopyCrypto.copyEncrypted(id, body);
				}
			}
			case COPY_INTO_FOLDER -> {
				// 刚右键了抄这一篇的空白抄本，目标在被锁的文件夹里：用文件夹钥匙按目标 ID 加密原正文后存进去
				Optional<FolderCopy.Packed> packed = FolderCopy.unpack(body);
				if (packed.isPresent() && PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.COPY),
						packed.get().source(), NEW_NOTE_INTENT_NANOS)) {
					CopyCrypto.copyIntoFolder(id, packed.get());
				}
			}
			case ENCRYPT_NEW_FOLDER -> {
				// 建在被锁的文件夹里：三种空白笔记右键新建都会走到这里（结果是加密，不会降级成明文）
				boolean item = PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.PLAIN_BLANK,
						PalaceInteractions.ItemUse.LOCKED_BLANK, PalaceInteractions.ItemUse.COPY), null, NEW_NOTE_INTENT_NANOS);
				boolean requested = CommandIntents.take(CommandIntents.Group.NEW_NOTE, CommandIntents.Kind.NEW_NOTE) || item;
				if (requested) {
					FolderCrypto.newFolderNote(id, title, body);
				}
			}
			case ENCRYPT, ENCRYPT_NEW -> {
				// 亲手输入的 /palace encrypt、newsecret，或者刚右键了空白上锁笔记 / 待上锁的笔记书
				boolean item = kind == CryptoAction.ENCRYPT
						? PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.PENDING_LOCK), id, NEW_NOTE_INTENT_NANOS)
						: PalaceInteractions.takeLocalItemUse(Set.of(PalaceInteractions.ItemUse.LOCKED_BLANK), null, NEW_NOTE_INTENT_NANOS);
				boolean requested = CommandIntents.take(CommandIntents.Group.ENCRYPT,
						CommandIntents.Kind.ENCRYPT, CommandIntents.Kind.NEW_SECRET) || item;
				if (!requested) {
					return;
				}
				PlainCrypto.encrypt(kind, id, title, body);
			}
			// 解除加密 = 把明文永久写回硬盘，所以即使已经解锁也要重新输一次密码
			case DECRYPT -> {
				if (!takeDecryptIntent(id, body)) {
					PasswordScreen.tell("message.memorypalace.decrypt_unrequested");
					return;
				}
				PlainCrypto.decrypt(id, action.title(), body);
			}
			default -> {}
		}
	}

	private static int parseCount(String text) {
		try {
			return Math.max(0, Integer.parseInt(text.strip()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
