package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每篇加密笔记"怎么再加密回去"：
 * - 新格式 MPAL2（文件夹独立密码等）：打开时解出的内容钥匙 + 当前的信封（锁不变，只换正文）
 * - 没登记的：旧格式 MPAL1，用保险库主密钥
 * 只在内存里，上锁、离开世界时清空（内容钥匙清零）。只在渲染线程使用。
 */
public final class NoteSealers {
	private record Sealer(ContentKey key, String envelope) {}

	private static final Map<UUID, Sealer> SEALERS = new HashMap<>();

	private NoteSealers() {}

	/** 打开了一篇 MPAL2 笔记：记下它的内容钥匙和信封。 */
	public static void remember(UUID noteId, ContentKey key, String envelope) {
		Sealer old = SEALERS.put(noteId, new Sealer(key, envelope.strip()));
		if (old != null && old.key() != key) {
			old.key().destroy();
		}
	}

	/** 这篇加密笔记现在能不能加密 / 解密：MPAL2 的要登记过，MPAL1 的要保险库已解锁。 */
	public static boolean available(UUID noteId) {
		return SEALERS.containsKey(noteId) || ClientKeys.unlocked();
	}

	/** 是不是 MPAL2 笔记（登记过）。 */
	public static boolean isEnvelope(UUID noteId) {
		return SEALERS.containsKey(noteId);
	}

	/** 把明文加密成要存回去的正文。不能加密时（已上锁）→ IllegalStateException。 */
	public static String seal(UUID noteId, String plain) {
		Sealer sealer = SEALERS.get(noteId);
		if (sealer == null) {
			if (!ClientKeys.unlocked()) {
				throw new IllegalStateException("已上锁");
			}
			return NoteCipher.encrypt(ClientKeys.key(), noteId, plain);
		}
		ClientKeys.touch();
		try {
			return NoteEnvelope.withBody(noteId, sealer.envelope(), sealer.key(), plain);
		} catch (WrongKeyException e) {
			throw new IllegalStateException("内容钥匙和这篇笔记对不上", e);
		}
	}

	/** 解开存着的正文（编辑器恢复草稿时用）。 */
	public static String open(UUID noteId, String stored) throws WrongKeyException {
		Sealer sealer = SEALERS.get(noteId);
		if (sealer != null && NoteEnvelope.isEnvelope(stored)) {
			return NoteEnvelope.decrypt(noteId, stored, sealer.key());
		}
		if (!ClientKeys.unlocked()) {
			throw new WrongKeyException("已上锁");
		}
		return NoteCipher.decrypt(ClientKeys.key(), noteId, stored);
	}

	/** 保存成功了：之后在这份新正文的基础上换正文（锁可能在别处被改过，以存进去的为准）。 */
	public static void saved(UUID noteId, String stored) {
		Sealer sealer = SEALERS.get(noteId);
		if (sealer != null && NoteEnvelope.isEnvelope(stored)) {
			SEALERS.put(noteId, new Sealer(sealer.key(), stored.strip()));
		}
	}

	/** 上锁、离开世界：内容钥匙全部清零。 */
	public static void clear() {
		SEALERS.values().forEach(s -> s.key().destroy());
		SEALERS.clear();
	}
}
