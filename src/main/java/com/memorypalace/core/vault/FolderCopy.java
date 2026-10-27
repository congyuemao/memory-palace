package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteEnvelope;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 把一篇笔记抄进"设了文件夹密码"的文件夹（抄本物品右键）。服务端不能先把正文以明文写进去，所以分几步：
 * 1. 服务端在目标文件夹里建一篇只有默认标题的占位笔记，自己记一条抄写记录（CopyJobs：谁、原笔记、目标、
 * 占位正文的指纹、文件夹锁），抄本物品只记记录的 ID（物品不消耗；物品数据可以伪造，不当凭证）
 * 2. 把原笔记正文（明文，或加密笔记的密文）发给客户端；客户端解开（需要时）、用目标文件夹的钥匙按目标的 ID 加密，
 *    保存时核对占位正文的指纹——目标被外部改过就冲突，不覆盖
 * 3. 服务端看到这篇从占位存成了只有那把文件夹锁的密文，才把抄本换成笔记书、说"抄好了"
 * 中途取消、保存失败：物品还在，再右键接着往同一篇里抄（next 决定怎么做）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class FolderCopy {
	private FolderCopy() {}

	/** 抄本物品再次右键时怎么做。 */
	public enum Next {
		/** 没有目标、目标没了、不是自己的、被外部改过：另建一篇新的目标（被改过的那篇不动） */
		CREATE,
		/** 目标还是占位的样子（上次取消了或保存失败）：再发一次，接着往这篇里抄 */
		RESEND,
		/** 目标已经存成密文（抄好了，只是还没换成笔记书）：换成笔记书 */
		FINISH
	}

	/**
	 * 只看服务端自己记的抄写记录（CopyJobs），不看物品上写的东西。
	 * @param target          记录里那篇目标现在的样子（readNote 的结果），没有就是 empty
	 * @param placeholderHash 记录里占位正文的指纹（Vault.hash）
	 * @param owned           目标是不是这个人的
	 * @param lockId          记录里目标文件夹的锁 ID（调用方还要确认它就是文件夹现在的锁）
	 */
	public static Next next(Optional<Vault.Read> target, String placeholderHash, boolean owned, String lockId) {
		if (target.isEmpty() || !owned) {
			return Next.CREATE;
		}
		String body = target.get().body();
		if (sealedWith(body, lockId)) {
			return Next.FINISH;
		}
		return Vault.hash(body).equals(placeholderHash) ? Next.RESEND : Next.CREATE;
	}

	/** 正文是不是格式正确、只有 lockId 这一把文件夹锁的 MPAL2 密文（抄好了的样子）。不能只看"是不是密文"。 */
	public static boolean sealedWith(String body, String lockId) {
		if (!NoteEnvelope.isEnvelope(body)) {
			return false;
		}
		try {
			return NoteEnvelope.locks(body).equals(java.util.List.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId)));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** 发给客户端的内容：原笔记 ID、目标现在正文的指纹（保存时核对）、原笔记正文。 */
	public record Packed(UUID source, String targetHash, String body) {}

	public static String pack(UUID source, String targetHash, String body) {
		return source + ":" + targetHash + ":" + body;
	}

	/** 格式不对 → empty。正文里可以有冒号、换行。 */
	public static Optional<Packed> unpack(String packed) {
		if (packed == null) {
			return Optional.empty();
		}
		int first = packed.indexOf(':');
		int second = first < 0 ? -1 : packed.indexOf(':', first + 1);
		if (second < 0) {
			return Optional.empty();
		}
		String hash = packed.substring(first + 1, second);
		if (!hash.matches("[0-9a-f]{64}")) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Packed(UUID.fromString(packed.substring(0, first)), hash, packed.substring(second + 1)));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** 用文件夹钥匙加密好的一篇：新的内容钥匙（存好后客户端记下，之后编辑保存用）+ 密文。 */
	public record Sealed(ContentKey key, String text) {}

	/** 用文件夹钥匙给 noteId 这篇加密：新的内容钥匙，只有一把 f 锁（保险库密码打不开）。 */
	public static Sealed seal(UUID noteId, String plain, String lockId, MasterKey folderKey) {
		ContentKey contentKey = ContentKey.random();
		byte[] wrapping = NoteEnvelope.folderWrappingKey(folderKey);
		try {
			return new Sealed(contentKey, NoteEnvelope.seal(noteId, contentKey, plain,
					Map.of(new NoteEnvelope.Lock(NoteEnvelope.Kind.FOLDER, lockId), wrapping)));
		} finally {
			java.util.Arrays.fill(wrapping, (byte) 0);
		}
	}
}
