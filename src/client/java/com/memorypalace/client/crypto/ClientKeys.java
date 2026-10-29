package com.memorypalace.client.crypto;

import com.memorypalace.client.EditorScreen;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.render.RasterTextures;
import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.CryptoPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 客户端的"钥匙管家"：解锁后的主密钥只存在这里（内存），上锁时清零。
 * 也负责向服务端要/写钥匙圈文件（里面只有锁着的主密钥，没有密码）。只在渲染线程使用。
 *
 * "代次"：每次上锁或离开世界都 +1。后台还在算的密码任务、还没回来的请求，回来时发现代次变了就作废，
 * 避免"已经上锁/退出了，迟到的结果又把它解锁"。
 */
public final class ClientKeys {
	private static MasterKey key;
	private static long lastUse;
	private static int generation;
	private static final List<Waiter> WAITING = new ArrayList<>();
	private static int writeCounter;
	private static final Map<Integer, Consumer<Boolean>> WRITES = new HashMap<>();

	private ClientKeys() {}

	public static boolean unlocked() {
		return key != null && !key.isDestroyed();
	}

	public static int generation() {
		return generation;
	}

	/** 当前主密钥（必须已解锁）。用一次就算"碰过"，推迟自动上锁。 */
	public static MasterKey key() {
		touch();
		return key;
	}

	public static void setKey(MasterKey newKey) {
		if (key != null && key != newKey) {
			key.destroy();
		}
		key = newKey;
		touch();
	}

	// ---- 收件箱钥匙（解锁时从钥匙圈里解出来，只在内存里，上锁时清零） ----

	private static byte[] boxPublic;
	private static byte[] boxPrivate;

	/**
	 * 用 keyring 解锁出了 newKey（输密码、设新密码、恢复）：设好主密钥，顺便解出收件箱私钥备用。
	 * 旧钥匙圈还没有收件箱钥匙的：生成一对补进去并写回服务端，存好之后才开始用。
	 */
	public static void unlockedWith(String keyring, MasterKey newKey) {
		setKey(newKey);
		forgetBox();
		try {
			if (KeyRing.hasBox(keyring)) {
				rememberBox(keyring, newKey);
				return;
			}
			String withBox = KeyRing.withBox(keyring, newKey);
			int gen = generation;
			writeKeyring(withBox, keyring, ok -> {
				if (ok && gen == generation && key == newKey) {
					rememberBox(withBox, newKey);
				}
			});
		} catch (RuntimeException e) {
			MemoryPalace.LOGGER.warn("准备收件箱钥匙失败", e);
		}
	}

	private static void rememberBox(String keyring, MasterKey master) {
		try {
			boxPublic = KeyRing.boxPublic(keyring);
			boxPrivate = KeyRing.boxPrivate(keyring, master);
		} catch (WrongKeyException | RuntimeException e) {
			forgetBox();
			MemoryPalace.LOGGER.warn("解不开收件箱私钥", e);
		}
	}

	private static void forgetBox() {
		if (boxPrivate != null) {
			Arrays.fill(boxPrivate, (byte) 0);
		}
		boxPrivate = null;
		boxPublic = null;
	}

	/** 有没有解出来的收件箱私钥（已解锁、钥匙圈里有收件箱钥匙）。 */
	public static boolean hasBox() {
		return unlocked() && boxPrivate != null;
	}

	/** 把正文里的收件箱密文行（MPALBOX1）解开；没有收件箱私钥时为 null。解不开的行换成提示。 */
	public static String openSealed(String body) {
		if (!hasBox()) {
			return null;
		}
		touch();
		return InboxCipher.openAll(boxPrivate, boxPublic, body,
				Component.translatable("message.memorypalace.inbox_unreadable").getString());
	}

	/** 玩家在看/写加密笔记：推迟自动上锁。 */
	public static void touch() {
		lastUse = System.currentTimeMillis();
	}

	/**
	 * 上锁：清零主密钥、清掉公式缓存（里面有公式原文），关掉正在显示加密笔记的界面。
	 * 调用前要先确认没有没保存的加密草稿（见 tick），否则草稿会丢。
	 */
	public static void lock(boolean announce) {
		Minecraft mc = Minecraft.getInstance();
		boolean wasUnlocked = unlocked() || FolderKeys.any() || ReadPasswords.any();
		// 正在编辑的加密笔记（即使当前停在"放弃修改？"对话框上）有没保存的内容：先加密暂存再尝试保存
		EditorScreen editor = activeEditor;
		if (wasUnlocked && editor != null) {
			editor.stashDraftAndSave();
		}
		activeEditor = null;
		generation++;
		if (key != null) {
			key.destroy();
			key = null;
		}
		forgetBox();
		// 正在逐篇加密 / 解密的文件夹：排着队的取消（解密的是明文），报告未处理几篇
		FolderBatch.onLock();
		// 文件夹钥匙、每篇笔记的内容钥匙一起清零（草稿已经在上面用它们加密暂存过了）
		FolderKeys.clear();
		NoteSealers.clear();
		ReadPasswords.clear(); // 别人笔记的阅读密码钥匙：下次打开要重输
		RasterTextures.clear();
		BookScreen.clearMathFailures();
		Screen screen = mc.gui.screen();
		if (screen instanceof BookScreen book && book.isEncrypted()
				|| screen instanceof com.memorypalace.client.HistoryScreen history && history.secret()
				|| screen instanceof PasswordScreen || screen instanceof RecoveryKeyScreen) {
			mc.gui.setScreen(null);
		}
		if (announce && wasUnlocked && mc.player != null) {
			mc.player.sendSystemMessage(Component.translatable("message.memorypalace.locked"));
		}
	}

	/** 离开世界：上锁，并丢掉所有还没回来的请求。 */
	public static void disconnect() {
		lock(false);
		WAITING.clear();
		WRITES.clear();
		DRAFTS.clear();
		NoteSaver.clear();
	}

	/** 上锁前这么多秒开始在书页上提醒。 */
	public static final int WARNING_SECONDS = 30;

	/** 每个客户端刻调用：闲置超时自动上锁（没保存的草稿会先加密暂存，见 lock）。 */
	public static void tick() {
		long left = secondsUntilLock();
		if (left == 0) {
			lock(true);
		}
	}

	/**
	 * 离自动上锁还有几秒（向上取整）；没解锁或设置成"不自动上锁"时为 -1。
	 * 计时从最后一次操作（打字、输入法拼字、点击、滚动、移动鼠标）开始算，所以正在写的时候永远不会锁。
	 */
	public static long secondsUntilLock() {
		int minutes = BookSettings.autoLock().minutes;
		// 只解锁了某个文件夹、只输过别人笔记的阅读密码（没输保险库密码）也要按时上锁
		if (!(unlocked() || FolderKeys.any() || ReadPasswords.any()) || minutes <= 0) {
			return -1;
		}
		long leftMs = minutes * 60_000L - (System.currentTimeMillis() - lastUse);
		return Math.max(0, (leftMs + 999) / 1000);
	}

	// ---- 加密草稿暂存 ----

	// 正在编辑的加密笔记（上锁时要先暂存它的草稿）
	private static EditorScreen activeEditor;
	// 笔记 ID → 上锁前没保存成功的草稿（密文，放在内存里是安全的）。下次解锁后打开编辑器时恢复
	private static final Map<java.util.UUID, String> DRAFTS = new HashMap<>();

	public static void setActiveEditor(EditorScreen editor) {
		activeEditor = editor;
	}

	public static void clearActiveEditor(EditorScreen editor) {
		if (activeEditor == editor) {
			activeEditor = null;
		}
	}

	public static void stashDraft(java.util.UUID noteId, String cipher) {
		DRAFTS.put(noteId, cipher);
	}

	public static void dropDraft(java.util.UUID noteId) {
		DRAFTS.remove(noteId);
	}

	/** 取出（并移除）这篇笔记暂存的草稿密文；没有就是 null。 */
	public static String takeDraft(java.util.UUID noteId) {
		return DRAFTS.remove(noteId);
	}

	// ---- 钥匙圈文件 ----

	/**
	 * 向服务端要钥匙圈，拿到后在渲染线程回调（"" 表示还没启用过加密）。
	 * 读取失败或没有权限时不回调，直接提示——绝不能把"读不出来"当成"还没启用"，否则会覆盖掉原来的钥匙圈。
	 */
	public static void withKeyring(Consumer<String> callback) {
		withKeyring(callback, () -> {});
	}

	/** 同上；读取失败或没有权限时（提示之后）调用 failed。离开世界时丢掉的请求两个都不调用。 */
	public static void withKeyring(Consumer<String> callback, Runnable failed) {
		WAITING.add(new Waiter(callback, failed));
		if (WAITING.size() == 1) {
			ClientPlayNetworking.send(CryptoPayloads.KeyringRequest.INSTANCE);
		}
	}

	public static void onKeyringData(CryptoPayloads.KeyringData data) {
		List<Waiter> waiters = List.copyOf(WAITING);
		WAITING.clear();
		switch (data.status()) {
			case "ok" -> waiters.forEach(w -> w.callback().accept(data.content()));
			case "missing" -> waiters.forEach(w -> w.callback().accept(""));
			default -> {
				PasswordScreen.tell("denied".equals(data.status())
						? "command.memorypalace.crypto_denied" : "message.memorypalace.keyring_read_failed");
				waiters.forEach(w -> w.failed().run());
			}
		}
	}

	private record Waiter(Consumer<String> callback, Runnable failed) {}

	/**
	 * 把新钥匙圈写回服务端。oldContent 是写之前的内容（还没有时为 ""），服务端会核对，防止过期写入覆盖新内容。
	 * done(true) 表示确认已经存进硬盘——在这之前不能用新的主密钥加密任何东西。
	 */
	public static void writeKeyring(String newContent, String oldContent, Consumer<Boolean> done) {
		int id = ++writeCounter;
		int gen = generation;
		WRITES.put(id, ok -> done.accept(ok && gen == generation));
		ClientPlayNetworking.send(new CryptoPayloads.KeyringWrite(id, oldContent.isEmpty() ? "" : Vault.hash(oldContent), newContent));
	}

	public static void onKeyringWriteResult(CryptoPayloads.KeyringWriteResult result) {
		Consumer<Boolean> callback = WRITES.remove(result.requestId());
		if (callback == null) {
			return;
		}
		boolean ok = "OK".equals(result.result());
		if (!ok) {
			PasswordScreen.tell(switch (result.result()) {
				case "CONFLICT" -> "message.memorypalace.keyring_conflict";
				case "DENIED" -> "command.memorypalace.crypto_denied";
				default -> "message.memorypalace.keyring_write_failed";
			});
		}
		callback.accept(ok);
	}

	/**
	 * 确保已经解锁再做 then：没启用过加密 → 先设置密码；已启用 → 输密码解锁。
	 * 用户取消时回到 returnTo（可以是 null = 关掉界面）。
	 */
	public static void requireUnlocked(Screen returnTo, Runnable then) {
		if (unlocked()) {
			then.run();
			return;
		}
		withKeyring(content -> Minecraft.getInstance().gui.setScreen(content.isEmpty()
				? PasswordScreen.setup(returnTo, then)
				: PasswordScreen.unlock(content, returnTo, then)));
	}
}
