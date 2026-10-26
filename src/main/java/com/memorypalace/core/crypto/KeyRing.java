package com.memorypalace.core.crypto;

/**
 * 钥匙圈：把主密钥"锁"起来存在 &lt;笔记库&gt;/.palace/keyring 里。密码本身不保存。
 * 这里所有方法都只处理文件内容（字符串），不读写文件——读写由调用方负责（服务端存文件，客户端做加解密）。
 *
 * 文件内容格式由实现决定（建议 java.util.Properties 风格的纯文本，带版本号），要求：
 * - 记录 KDF 参数（算法、内存、迭代次数、并行度）和随机盐，以后改默认参数也能打开旧文件
 * - "用密码锁住的主密钥"：密码 → Argon2id（password4j，默认 m=64MiB、t=3、p=1，输出 32 字节）→ 包装密钥，
 *   用 AES-256-GCM（随机 12 字节 nonce）加密主密钥
 * - 可选的"用恢复密钥锁住的主密钥"：恢复密钥是 128 位随机数，熵已经够高，不需要 Argon2，
 *   用 HKDF-SHA256 或 SHA-256(域分隔前缀 + 密钥字节) 得到包装密钥即可；同样 AES-256-GCM
 * - GCM 的附加认证数据（AAD）要包含版本号和用途（"password" / "recovery"），防止两种包装互换
 * - 密码错 → 解包 GCM 校验失败 → WrongKeyException；不能有任何"密码对不对"的其他旁路信息（比如存密码哈希）
 * - 恢复密钥的文本形式：Base32（RFC 4648 字母表 A-Z2-7，无填充），每 4 个字符一组用 "-" 分隔，例如 "ABCD-EFGH-…"；
 *   输入时忽略大小写、空格和 "-"
 * - 所有随机数用 SecureRandom
 * - 方法用完的中间密钥字节、char[] 副本要尽量清零（Arrays.fill）
 * - 内容格式不对 / 版本不认识 → IllegalArgumentException（不是 WrongKeyException）
 *
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class KeyRing {
	/** 密码最短长度。 */
	public static final int MIN_PASSWORD = 6;

	private KeyRing() {}

	/**
	 * 新建钥匙圈的结果。
	 * @param content     要写进 keyring 文件的内容
	 * @param key         新生成的主密钥（已解锁状态）
	 * @param recoveryKey 恢复密钥文本；withRecovery = false 时为 null
	 */
	public record Created(String content, MasterKey key, String recoveryKey) {}

	/** 第一次启用加密：随机生成主密钥并用密码锁好。密码短于 MIN_PASSWORD → IllegalArgumentException。 */
	public static Created create(char[] password, boolean withRecovery) {
		throw new UnsupportedOperationException("TODO");
	}

	/** 用密码解锁。 */
	public static MasterKey unlock(String content, char[] password) throws WrongKeyException {
		throw new UnsupportedOperationException("TODO");
	}

	/** 用恢复密钥解锁（忘记密码时）。没有恢复密钥的钥匙圈 → WrongKeyException。 */
	public static MasterKey unlockWithRecovery(String content, String recoveryKey) throws WrongKeyException {
		throw new UnsupportedOperationException("TODO");
	}

	public static boolean hasRecovery(String content) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * 改密码：key 是已经解锁的主密钥（调用方已经用旧密码或恢复密钥验证过身份）。
	 * 用新盐重新包装，恢复密钥部分原样保留。返回新内容。新密码太短 → IllegalArgumentException。
	 */
	public static String changePassword(String content, MasterKey key, char[] newPassword) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * 生成新的恢复密钥（旧的立刻失效）。必须提供当前密码验证身份，密码错 → WrongKeyException。
	 * 返回 Created：content 是新内容，recoveryKey 是新恢复密钥，key 是解锁出来的主密钥。
	 */
	public static Created regenerateRecovery(String content, char[] password) throws WrongKeyException {
		throw new UnsupportedOperationException("TODO");
	}

	/** 删除恢复密钥。必须提供当前密码，密码错 → WrongKeyException。返回新内容。 */
	public static String removeRecovery(String content, char[] password) throws WrongKeyException {
		throw new UnsupportedOperationException("TODO");
	}
}
