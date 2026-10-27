package com.memorypalace.core.crypto;

import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
 * v1 格式：UTF-8 的 key=value 行，输出用 LF；输入也接受 CRLF 和空行。
 * 必填字段：version=1、kdf=argon2id、kdf.version=19、kdf.memoryKiB、kdf.iterations、
 * kdf.parallelism、kdf.length=32、password.salt、password.wrap。
 * 可选字段必须一起出现：recovery.kdf=HKDF-SHA-256、recovery.salt、recovery.wrap。
 * salt 为 16 字节；wrap 为 nonce(12) || 密文(32) || tag(16)，均用标准带填充 Base64。
 * 拒绝重复/未知字段；长度最多 4096 字符；读取参数范围为 8p ≤ m ≤ 262144 KiB、
 * 1 ≤ t ≤ 10、1 ≤ p ≤ 4，且 m*t ≤ 786432。读取参数不依赖新建时的默认值。
 * AAD 前缀为 memory-palace|keyring|1|，之后是用途及该包装的算法、参数和盐，字段用 | 分隔。
 * 恢复包装使用独立盐，HKDF info 为 memory-palace|keyring|1|recovery，输出 32 字节。
 *
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class KeyRing {
	/** 密码最短长度。 */
	public static final int MIN_PASSWORD = 6;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int MEMORY_KIB = PasswordKdf.MEMORY_KIB;
	private static final int ITERATIONS = PasswordKdf.ITERATIONS;
	private static final int PARALLELISM = PasswordKdf.PARALLELISM;
	private static final String DOMAIN = "memory-palace|keyring|1|";
	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final Set<String> PASSWORD_FIELDS = Set.of("version", "kdf", "kdf.version",
			"kdf.memoryKiB", "kdf.iterations", "kdf.parallelism", "kdf.length", "password.salt", "password.wrap");
	private static final Set<String> RECOVERY_FIELDS = Set.of("recovery.kdf", "recovery.salt", "recovery.wrap");
	// 收件箱钥匙（可选，两个字段一起出现）：box.public = 公钥（X.509，明文，随手记加密用，不需要密码）；
	// box.wrap = 用主密钥派生的钥匙包好的私钥（PKCS#8），AAD 绑定公钥
	private static final Set<String> BOX_FIELDS = Set.of("box.public", "box.wrap");

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
		checkPassword(password);
		byte[] secret = randomBytes(MasterKey.LENGTH);
		MasterKey key = null;
		try {
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("version", "1");
			setPassword(fields, secret, password);
			String recovery = withRecovery ? setRecovery(fields, secret) : null;
			setBox(fields, secret);
			String content = serialize(fields);
			key = new MasterKey(secret);
			return new Created(content, key, recovery);
		} catch (RuntimeException | Error e) {
			if (key != null) {
				key.destroy();
			}
			throw e;
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/** 用密码解锁。 */
	public static MasterKey unlock(String content, char[] password) throws WrongKeyException {
		return unlockPassword(parse(content), password);
	}

	/** 用恢复密钥解锁（忘记密码时）。没有恢复密钥的钥匙圈 → WrongKeyException。 */
	public static MasterKey unlockWithRecovery(String content, String recoveryKey) throws WrongKeyException {
		Map<String, String> fields = parse(content);
		if (!fields.containsKey("recovery.wrap")) {
			throw NoteCipher.wrongKey();
		}
		byte[] recovery = decodeRecovery(recoveryKey);
		byte[] wrappingKey = null;
		try {
			wrappingKey = recoveryWrappingKey(recovery, decode(fields.get("recovery.salt"), 16));
			return unwrap(fields, "recovery", wrappingKey);
		} finally {
			NoteCipher.wipe(recovery);
			NoteCipher.wipe(wrappingKey);
		}
	}

	public static boolean hasRecovery(String content) {
		return parse(content).containsKey("recovery.wrap");
	}

	/**
	 * 改密码：key 是已经解锁的主密钥（调用方已经用旧密码或恢复密钥验证过身份）。
	 * 用新盐重新包装，恢复密钥部分原样保留。返回新内容。新密码太短 → IllegalArgumentException。
	 */
	public static String changePassword(String content, MasterKey key, char[] newPassword) {
		checkPassword(newPassword);
		Map<String, String> fields = parse(content);
		byte[] secret = NoteCipher.copyKey(key);
		try {
			// 恢复包装不依赖密码盐或参数，改密码时保留全部 recovery 字段。
			setPassword(fields, secret, newPassword);
			return serialize(fields);
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/**
	 * 生成新的恢复密钥（旧的立刻失效）。必须提供当前密码验证身份，密码错 → WrongKeyException。
	 * 返回 Created：content 是新内容，recoveryKey 是新恢复密钥，key 是解锁出来的主密钥。
	 */
	public static Created regenerateRecovery(String content, char[] password) throws WrongKeyException {
		Map<String, String> fields = parse(content);
		MasterKey key = unlockPassword(fields, password);
		byte[] secret = null;
		boolean returned = false;
		try {
			secret = NoteCipher.copyKey(key);
			String recovery = setRecovery(fields, secret);
			Created result = new Created(serialize(fields), key, recovery);
			returned = true;
			return result;
		} finally {
			NoteCipher.wipe(secret);
			if (!returned) {
				key.destroy();
			}
		}
	}

	/** 删除恢复密钥。必须提供当前密码，密码错 → WrongKeyException。返回新内容。 */
	public static String removeRecovery(String content, char[] password) throws WrongKeyException {
		Map<String, String> fields = parse(content);
		MasterKey key = unlockPassword(fields, password);
		try {
			RECOVERY_FIELDS.forEach(fields::remove);
			return serialize(fields);
		} finally {
			key.destroy();
		}
	}

	// ---- 收件箱钥匙 ----

	/** 钥匙圈里有没有收件箱钥匙。 */
	public static boolean hasBox(String content) {
		return parse(content).containsKey("box.public");
	}

	/** 收件箱公钥（不需要密码）；还没有时为 null。 */
	public static byte[] boxPublic(String content) {
		String value = parse(content).get("box.public");
		return value == null ? null : decode(value, 44);
	}

	/**
	 * 已有收件箱钥匙就原样返回；没有就生成一对，用 key 锁好私钥，返回新内容（旧钥匙圈第一次解锁后补上）。
	 * key 必须是这个钥匙圈解锁出来的主密钥。
	 */
	public static String withBox(String content, MasterKey key) {
		Map<String, String> fields = parse(content);
		if (fields.containsKey("box.public")) {
			return content;
		}
		byte[] secret = NoteCipher.copyKey(key);
		try {
			setBox(fields, secret);
			return serialize(fields);
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/** 解开收件箱私钥（PKCS#8，调用方用完清零）。没有收件箱钥匙、主密钥不对、被改过 → WrongKeyException。 */
	public static byte[] boxPrivate(String content, MasterKey key) throws WrongKeyException {
		Map<String, String> fields = parse(content);
		if (!fields.containsKey("box.wrap")) {
			throw NoteCipher.wrongKey();
		}
		byte[] secret = NoteCipher.copyKey(key);
		byte[] wrappingKey = null;
		try {
			wrappingKey = boxWrappingKey(secret);
			return NoteCipher.open(wrappingKey, aad(fields, "box"), decodeBetween(fields.get("box.wrap"), 60, 256));
		} finally {
			NoteCipher.wipe(secret);
			NoteCipher.wipe(wrappingKey);
		}
	}

	private static void setBox(Map<String, String> fields, byte[] secret) {
		InboxCipher.BoxKeys keys = InboxCipher.generate();
		byte[] wrappingKey = null;
		try {
			fields.put("box.public", Base64.getEncoder().encodeToString(keys.publicKey()));
			wrappingKey = boxWrappingKey(secret);
			fields.put("box.wrap", Base64.getEncoder().encodeToString(
					NoteCipher.seal(wrappingKey, aad(fields, "box"), keys.privateKey())));
		} finally {
			NoteCipher.wipe(keys.privateKey());
			NoteCipher.wipe(wrappingKey);
		}
	}

	private static byte[] boxWrappingKey(byte[] secret) {
		try {
			return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract().addIKM(secret)
					.thenExpand((DOMAIN + "box").getBytes(StandardCharsets.UTF_8), MasterKey.LENGTH));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 HKDF-SHA-256");
		}
	}

	private static void checkPassword(char[] password) {
		if (password == null || password.length < MIN_PASSWORD) {
			throw new IllegalArgumentException("密码长度不足");
		}
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static void setPassword(Map<String, String> fields, byte[] secret, char[] password) {
		fields.put("kdf", "argon2id");
		fields.put("kdf.version", "19");
		fields.put("kdf.memoryKiB", Integer.toString(MEMORY_KIB));
		fields.put("kdf.iterations", Integer.toString(ITERATIONS));
		fields.put("kdf.parallelism", Integer.toString(PARALLELISM));
		fields.put("kdf.length", "32");
		fields.put("password.salt", Base64.getEncoder().encodeToString(randomBytes(16)));
		byte[] wrappingKey = passwordWrappingKey(fields, password);
		try {
			fields.put("password.wrap", Base64.getEncoder().encodeToString(
					NoteCipher.seal(wrappingKey, aad(fields, "password"), secret)));
		} finally {
			NoteCipher.wipe(wrappingKey);
		}
	}

	private static MasterKey unlockPassword(Map<String, String> fields, char[] password) throws WrongKeyException {
		byte[] wrappingKey = passwordWrappingKey(fields, password);
		try {
			return unwrap(fields, "password", wrappingKey);
		} finally {
			NoteCipher.wipe(wrappingKey);
		}
	}

	private static MasterKey unwrap(Map<String, String> fields, String purpose, byte[] wrappingKey)
			throws WrongKeyException {
		byte[] secret = NoteCipher.open(wrappingKey, aad(fields, purpose), decode(fields.get(purpose + ".wrap"), 60));
		try {
			return new MasterKey(secret);
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	private static byte[] passwordWrappingKey(Map<String, String> fields, char[] password) {
		if (password == null) {
			throw new IllegalArgumentException("密码不能为空");
		}
		byte[] salt = decode(fields.get("password.salt"), 16);
		try {
			return PasswordKdf.derive(password, salt, Integer.parseInt(fields.get("kdf.memoryKiB")),
					Integer.parseInt(fields.get("kdf.iterations")), Integer.parseInt(fields.get("kdf.parallelism")));
		} finally {
			NoteCipher.wipe(salt);
		}
	}

	private static String setRecovery(Map<String, String> fields, byte[] secret) {
		byte[] recovery = randomBytes(16);
		byte[] salt = randomBytes(16);
		byte[] wrappingKey = null;
		try {
			fields.put("recovery.kdf", "HKDF-SHA-256");
			fields.put("recovery.salt", Base64.getEncoder().encodeToString(salt));
			wrappingKey = recoveryWrappingKey(recovery, salt);
			fields.put("recovery.wrap", Base64.getEncoder().encodeToString(
					NoteCipher.seal(wrappingKey, aad(fields, "recovery"), secret)));
			return encodeRecovery(recovery);
		} finally {
			NoteCipher.wipe(recovery);
			NoteCipher.wipe(wrappingKey);
			NoteCipher.wipe(salt);
		}
	}

	private static byte[] recoveryWrappingKey(byte[] recovery, byte[] salt) {
		try {
			// 随机恢复码已有 128 位熵；使用 JDK 的 RFC 5869 实现，并隔离用途。
			return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract()
					.addIKM(recovery).addSalt(salt)
					.thenExpand((DOMAIN + "recovery").getBytes(StandardCharsets.UTF_8), MasterKey.LENGTH));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 HKDF-SHA-256");
		} finally {
			NoteCipher.wipe(salt);
		}
	}

	private static byte[] aad(Map<String, String> fields, String purpose) {
		// 版本、用途和本包装的 KDF 元数据都受认证；两个包装互不依赖，允许独立轮换。
		String metadata = switch (purpose) {
			case "password" -> fields.get("kdf") + "|" + fields.get("kdf.version") + "|" + fields.get("kdf.memoryKiB")
					+ "|" + fields.get("kdf.iterations") + "|" + fields.get("kdf.parallelism")
					+ "|" + fields.get("kdf.length") + "|" + fields.get("password.salt");
			case "recovery" -> fields.get("recovery.kdf") + "|" + fields.get("recovery.salt");
			// 私钥和公钥绑在一起：把公钥换掉，私钥就解不开了
			default -> fields.get("box.public");
		};
		return (DOMAIN + purpose + "|" + metadata).getBytes(StandardCharsets.UTF_8);
	}

	/** v1 是严格的 key=value 文本，不支持 Properties 的转义、续行或重复字段。 */
	private static Map<String, String> parse(String content) {
		if (content == null || content.length() > 4096) {
			throw invalidContent();
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (String line : content.split("\r?\n", -1)) {
			if (line.isEmpty()) {
				continue;
			}
			int equals = line.indexOf('=');
			if (equals < 1) {
				throw invalidContent();
			}
			String name = line.substring(0, equals);
			if ((!PASSWORD_FIELDS.contains(name) && !RECOVERY_FIELDS.contains(name) && !BOX_FIELDS.contains(name))
					|| fields.putIfAbsent(name, line.substring(equals + 1)) != null) {
				throw invalidContent();
			}
		}
		if (!fields.keySet().containsAll(PASSWORD_FIELDS)
				|| !"1".equals(fields.get("version")) || !"argon2id".equals(fields.get("kdf"))
				|| !"19".equals(fields.get("kdf.version")) || !"32".equals(fields.get("kdf.length"))) {
			throw invalidContent();
		}
		// 先验证公开格式和资源上限，再执行昂贵的 KDF，防止恶意参数耗尽内存。
		int memory = number(fields.get("kdf.memoryKiB"), 262144);
		int iterations = number(fields.get("kdf.iterations"), 10);
		int parallelism = number(fields.get("kdf.parallelism"), 4);
		if (memory < 8 * parallelism || (long) memory * iterations > 786432) {
			throw invalidContent();
		}
		decode(fields.get("password.salt"), 16);
		decode(fields.get("password.wrap"), 60);
		if (RECOVERY_FIELDS.stream().anyMatch(fields::containsKey)) {
			if (!fields.keySet().containsAll(RECOVERY_FIELDS) || !"HKDF-SHA-256".equals(fields.get("recovery.kdf"))) {
				throw invalidContent();
			}
			decode(fields.get("recovery.salt"), 16);
			decode(fields.get("recovery.wrap"), 60);
		}
		if (BOX_FIELDS.stream().anyMatch(fields::containsKey)) {
			if (!fields.keySet().containsAll(BOX_FIELDS)) {
				throw invalidContent();
			}
			byte[] publicKey = decode(fields.get("box.public"), 44);
			try {
				InboxCipher.publicKey(publicKey);
			} catch (IllegalArgumentException e) {
				throw invalidContent();
			}
			decodeBetween(fields.get("box.wrap"), 60, 256);
		}
		return fields;
	}

	private static byte[] decodeBetween(String text, int min, int max) {
		try {
			byte[] bytes = Base64.getDecoder().decode(text);
			if (bytes.length < min || bytes.length > max || !Base64.getEncoder().encodeToString(bytes).equals(text)) {
				throw invalidContent();
			}
			return bytes;
		} catch (IllegalArgumentException e) {
			throw invalidContent();
		}
	}

	private static int number(String text, int maximum) {
		if (text == null || !text.matches("[1-9][0-9]{0,5}")) {
			throw invalidContent();
		}
		int value = Integer.parseInt(text);
		if (value > maximum) {
			throw invalidContent();
		}
		return value;
	}

	private static byte[] decode(String text, int length) {
		try {
			byte[] bytes = Base64.getDecoder().decode(text);
			// 只比较公开的编码格式；秘密和认证标签不在应用代码中比较。
			if (bytes.length != length || !Base64.getEncoder().encodeToString(bytes).equals(text)) {
				throw invalidContent();
			}
			return bytes;
		} catch (IllegalArgumentException e) {
			throw invalidContent();
		}
	}

	private static IllegalArgumentException invalidContent() {
		return new IllegalArgumentException("钥匙圈格式、版本或参数不受支持");
	}

	private static String serialize(Map<String, String> fields) {
		StringBuilder result = new StringBuilder();
		fields.forEach((name, value) -> result.append(name).append('=').append(value).append('\n'));
		return result.toString();
	}

	private static String encodeRecovery(byte[] bytes) {
		// RFC 4648：高位优先，每 5 位一个字符；128 位得到 26 个字符，末尾补零位而非 '='。
		char[] text = new char[32];
		try {
			int at = 0;
			for (int i = 0; i < 26; i++) {
				if (i > 0 && i % 4 == 0) {
					text[at++] = '-';
				}
				int value = 0;
				for (int bit = 0; bit < 5; bit++) {
					int position = i * 5 + bit;
					value = (value << 1) | (position < 128 ? (bytes[position / 8] >>> (7 - position % 8)) & 1 : 0);
				}
				text[at++] = BASE32.charAt(value);
			}
			return new String(text);
		} finally {
			Arrays.fill(text, '\0');
		}
	}

	private static byte[] decodeRecovery(String text) throws WrongKeyException {
		if (text == null) {
			throw NoteCipher.wrongKey();
		}
		byte[] bytes = new byte[16];
		boolean returned = false;
		try {
			int count = 0;
			int buffer = 0;
			int bits = 0;
			int at = 0;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '-' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
					continue;
				}
				if (c >= 'a' && c <= 'z') {
					c -= 'a' - 'A';
				}
				int value = BASE32.indexOf(c);
				if (value < 0 || ++count > 26) {
					throw NoteCipher.wrongKey();
				}
				buffer = (buffer << 5) | value;
				bits += 5;
				if (bits >= 8) {
					bits -= 8;
					bytes[at++] = (byte) (buffer >>> bits);
					buffer &= (1 << bits) - 1;
				}
			}
			// 拒绝非零尾部填充位，避免同一个恢复码出现不同的有效文本。
			if (count != 26 || buffer != 0) {
				throw NoteCipher.wrongKey();
			}
			returned = true;
			return bytes;
		} finally {
			if (!returned) {
				NoteCipher.wipe(bytes);
			}
		}
	}
}
