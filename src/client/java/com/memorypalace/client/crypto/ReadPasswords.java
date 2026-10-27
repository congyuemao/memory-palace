package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.mc.PalaceInteractions;
import com.memorypalace.mc.SharePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 读别人设了阅读密码的笔记（见 SharePayloads）：输密码 → 后台算出包装钥匙和验证码 → 把验证码交给服务端核对 →
 * 通过后服务端发来密文，用包装钥匙解开。
 * 钥匙按阶段走：PENDING（验证码发出去了）→ CONFIRMING（服务端说对了，等正文）→ KEYS（真的解开了正文）。
 * 只有真解开过的才长期记着（这次连接里再打开同一篇不用重输）；前两段 30 秒没有下文就清零作废。
 * 上锁（含闲置自动上锁）、离开世界时全部清零。
 * 服务端可能不怀好意，所以：
 * - 只在玩家几秒内亲手右键过这本书时才弹输密码框，题目参数也要不低于默认强度
 * - 一旦接受了某篇的题目，这篇在这次连接里就是"别人的受保护笔记"：只读，只认带同一把阅读密码锁、
 *   用手里的钥匙解得开的密文；明文一律不当普通笔记打开（不能借"刚输了密码"把明文冒充成受保护的笔记，骗玩家往里写）
 * 只在渲染线程使用。
 */
public final class ReadPasswords {
	private record Entry(String challenge, NoteEnvelope.PasswordKeys keys, long at) {}

	private static final int MAX_KEYS = 64;
	private static final int MAX_READER_NOTES = 4096;
	private static final long INTENT_NANOS = 10_000_000_000L;
	private static final long REPLY_NANOS = 30_000_000_000L;
	// 真解开过正文的（按最近使用排序，满了丢最久没用的）
	private static final LinkedHashMap<UUID, Entry> KEYS = new LinkedHashMap<>(16, 0.75f, true);
	// 验证码发出去、还没回音的
	private static final Map<UUID, Entry> PENDING = new HashMap<>();
	// 服务端说对了、正等着正文的
	private static final Map<UUID, Entry> CONFIRMING = new HashMap<>();
	// 这次连接里接受过题目的笔记（别人的受保护笔记）。满了就不再接受新题目，但绝不丢掉旧的（同 KNOWN_ENCRYPTED）
	private static final Set<UUID> READER_NOTES = new HashSet<>();
	// 正在显示的输密码界面（核对结果回来时在上面显示"密码不对"）
	private static PasswordScreen screen;
	private static UUID screenNote;

	private ReadPasswords() {}

	/** 服务端说这篇要阅读密码。记着这道题的钥匙就直接再核对一次，否则弹出输密码界面。 */
	public static void onPrompt(SharePayloads.PasswordPrompt prompt) {
		UUID id = prompt.noteId();
		// 玩家没刚右键过这本书：不是玩家要打开的，不理
		if (!PalaceInteractions.takeLocalOpen(id, INTENT_NANOS) || !NoteEnvelope.challengeIsStrong(prompt.challenge())) {
			return;
		}
		if (!READER_NOTES.contains(id)) {
			if (READER_NOTES.size() >= MAX_READER_NOTES) {
				return;
			}
			READER_NOTES.add(id);
		}
		cancel(id);
		Entry known = KEYS.remove(id);
		if (known != null && known.challenge().equals(prompt.challenge())) {
			ClientKeys.touch();
			Entry retry = new Entry(known.challenge(), known.keys(), System.nanoTime());
			PENDING.put(id, retry);
			send(id, retry);
			return;
		}
		if (known != null) {
			drop(id, known); // 题目变了（作者换了密码）：旧钥匙没用了
		}
		if (Minecraft.getInstance().gui.screen() == null) {
			ask(id, prompt.challenge(), Component.empty());
		}
	}

	private static void ask(UUID id, String challenge, Component status) {
		PasswordScreen s = PasswordScreen.readPassword(status, password -> NoteEnvelope.passwordKeys(challenge, password),
				keys -> {
					cancel(id);
					ClientKeys.touch(); // 开始算闲置自动上锁（之前可能从没解锁过任何东西）
					Entry entry = new Entry(challenge, keys, System.nanoTime());
					PENDING.put(id, entry);
					send(id, entry);
				},
				() -> cancel(id));
		screen = s;
		screenNote = id;
		Minecraft.getInstance().gui.setScreen(s);
	}

	private static void send(UUID id, Entry entry) {
		ClientPlayNetworking.send(new SharePayloads.PasswordAttempt(id, entry.challenge(),
				Base64.getEncoder().encodeToString(entry.keys().verifier())));
	}

	/** 这篇还没有结果的（发出去的、等正文的）作废清零。 */
	private static void cancel(UUID id) {
		drop(id, PENDING.remove(id));
		drop(id, CONFIRMING.remove(id));
	}

	private static void wipe(Entry entry) {
		if (entry != null) {
			entry.keys().wipe();
		}
	}

	/**
	 * 这篇的阅读密码钥匙作废了：清零，连同之前用它解开时记下的内容钥匙（NoteSealers）一起作废——
	 * 否则手里没有任何钥匙时自动上锁不会启动，那把内容钥匙会一直留在内存里。
	 */
	private static void drop(UUID id, Entry entry) {
		if (entry == null) {
			return;
		}
		entry.keys().wipe();
		if (READER_NOTES.contains(id)) {
			NoteSealers.forget(id);
		}
	}

	/** 核对结果。只认 PENDING 里的（30 秒内发出、没被取消的）。 */
	public static void onResult(SharePayloads.PasswordResult result) {
		UUID id = result.noteId();
		Entry pending = PENDING.remove(id);
		if (pending == null) {
			return; // 不是我们发的，或者已经超时 / 取消了
		}
		Minecraft mc = Minecraft.getInstance();
		switch (result.result()) {
			case "OK" -> {
				// 正文马上就到；用它真解开了才长期记着（见 confirmed），30 秒没到就作废
				wipe(CONFIRMING.put(id, new Entry(pending.challenge(), pending.keys(), System.nanoTime())));
			}
			case "WRONG", "WAIT" -> {
				drop(id, pending);
				Component why = "WAIT".equals(result.result())
						? PasswordScreen.t("read_wait", result.waitSeconds())
						: result.waitSeconds() > 0 ? PasswordScreen.t("read_wrong_wait", result.waitSeconds()) : PasswordScreen.t("wrong_password");
				if (screen != null && mc.gui.screen() == screen && id.equals(screenNote)) {
					screen.rejected(why);
				} else if (mc.gui.screen() == null) {
					ask(id, pending.challenge(), why);
				}
			}
			default -> {
				// CHANGED（作者刚换了或取消了密码）、UNAVAILABLE
				drop(id, pending);
				closeScreen(id);
				PasswordScreen.tell("CHANGED".equals(result.result())
						? "message.memorypalace.read_password_changed" : "message.memorypalace.note_unavailable");
			}
		}
	}

	private static void closeScreen(UUID id) {
		Minecraft mc = Minecraft.getInstance();
		if (screen != null && mc.gui.screen() == screen && id.equals(screenNote)) {
			mc.gui.setScreen(null);
		}
	}

	/** 每个客户端刻：30 秒没有下文的作废清零（服务端不回应时也不让钥匙一直留着）。 */
	public static void tick() {
		long now = System.nanoTime();
		expire(PENDING, now, true);
		expire(CONFIRMING, now, false);
	}

	private static void expire(Map<UUID, Entry> map, long now, boolean tellScreen) {
		Iterator<Map.Entry<UUID, Entry>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Entry> e = it.next();
			if (now - e.getValue().at() > REPLY_NANOS) {
				drop(e.getKey(), e.getValue());
				it.remove();
				Minecraft mc = Minecraft.getInstance();
				if (tellScreen && screen != null && mc.gui.screen() == screen && e.getKey().equals(screenNote)) {
					screen.rejected(PasswordScreen.t("read_timeout"));
				}
			}
		}
	}

	/** 用阅读密码钥匙真解开了这篇的正文：从"等正文"转成长期记着。 */
	static void confirmed(UUID id) {
		Entry entry = CONFIRMING.remove(id);
		if (entry == null) {
			return;
		}
		wipe(KEYS.put(id, entry));
		Iterator<Map.Entry<UUID, Entry>> it = KEYS.entrySet().iterator();
		while (KEYS.size() > MAX_KEYS && it.hasNext()) {
			Map.Entry<UUID, Entry> oldest = it.next();
			it.remove();
			drop(oldest.getKey(), oldest.getValue());
		}
		ClientKeys.touch();
	}

	/** 这篇是不是这次连接里接受过阅读密码题目的别人的笔记（只读、只认能用阅读密码解开的密文）。 */
	static boolean isReaderNote(UUID id) {
		return READER_NOTES.contains(id);
	}

	/**
	 * 这篇（MPAL2 密文 envelope）的阅读密码锁对应的包装钥匙（副本，调用方用完清零）；
	 * 没有核对通过的钥匙、或者密码锁已经换了 → null。
	 */
	static byte[] wrappingKeyFor(UUID id, String envelope) {
		Entry entry = CONFIRMING.containsKey(id) ? CONFIRMING.get(id) : KEYS.get(id);
		if (entry == null || !NoteEnvelope.isEnvelope(envelope)) {
			return null;
		}
		try {
			return NoteEnvelope.passwordLock(envelope).map(NoteEnvelope::passwordChallenge)
					.filter(entry.challenge()::equals).map(c -> entry.keys().wrappingKey().clone()).orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 手里有能打开这篇的阅读密码钥匙吗。 */
	static boolean matches(UUID id, String envelope) {
		byte[] key = wrappingKeyFor(id, envelope);
		if (key == null) {
			return false;
		}
		Arrays.fill(key, (byte) 0);
		return true;
	}

	/** 有没有记着的（或正在核对的）阅读密码钥匙：有就要按时自动上锁。 */
	static boolean any() {
		return !KEYS.isEmpty() || !PENDING.isEmpty() || !CONFIRMING.isEmpty();
	}

	/** 上锁：钥匙全部清零（READER_NOTES 保留：这些笔记仍然只认阅读密码打开）。 */
	static void clear() {
		KEYS.values().forEach(e -> e.keys().wipe());
		KEYS.clear();
		PENDING.values().forEach(e -> e.keys().wipe());
		PENDING.clear();
		CONFIRMING.values().forEach(e -> e.keys().wipe());
		CONFIRMING.clear();
		screen = null;
		screenNote = null;
	}

	/** 离开世界：连同"哪些是别人的受保护笔记"一起忘掉（下个服务器的笔记 ID 和这里无关）。 */
	static void disconnect() {
		clear();
		READER_NOTES.clear();
		PalaceInteractions.clearLocalOpen();
	}
}
