package com.memorypalace.core.crypto;

import javax.crypto.KDF;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 加密笔记的新格式 MPAL2（信封加密）：每篇笔记一把随机的内容钥匙（ContentKey），正文用它加密；
 * 内容钥匙再用一把或几把"锁"各包一份，一起存在笔记正文这一行里：
 * <pre>MPAL2:&lt;锁&gt;;&lt;锁&gt;…|&lt;正文密文&gt;
 * 锁 = &lt;种类&gt;.&lt;标识&gt;.&lt;Base64(nonce 12 || 包好的内容钥匙 32 || tag 16)&gt;
 * 正文密文 = Base64(nonce 12 || AES-256-GCM 密文和 16 字节 tag)</pre>
 * 锁的种类：
 * - u（USER）：某个玩家钥匙圈里的主密钥，标识 = 玩家 UUID（或单人时代的 "host"）
 * - f（FOLDER）：文件夹钥匙（文件夹独立密码），标识 = 文件夹钥匙的 ID
 * - p（PASSWORD）：单篇阅读密码，标识 = "a2id,内存KiB,迭代次数,并行度,Base64(盐),Base64(SHA-256(验证码))"（见下面"单篇阅读密码"）
 * 好处：加锁、换锁、分享都只改锁，不用重新加密正文；文件夹独立密码就是"只有 f 锁、没有 u 锁"。
 * <p>
 * 安全要点：
 * - 每次加密都用新的随机 nonce；AES-256-GCM 验证完整性
 * - 正文 AAD = "MPAL2|body|" + noteId；每把锁的 AAD = "MPAL2|lock|" + noteId + "|" + 种类 + "|" + 标识：
 *   密文和锁都绑定在这篇笔记上，挪到别的笔记、换种类或标识都会解密失败
 * - 主密钥不直接拿来包，先经 HKDF 派生出专门的包装钥匙（域分隔）
 * - 改正文、加锁之前先用给出的内容钥匙解一遍现有正文：钥匙不对就拒绝，免得一篇笔记里混进两把内容钥匙
 * - 格式不对（解析时）→ IllegalArgumentException；解密时格式不对、钥匙不对、被改过 → WrongKeyException
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteEnvelope {
	public static final String PREFIX = "MPAL2:";
	/** 一篇笔记最多这么多把锁。 */
	public static final int MAX_LOCKS = 32;
	private static final int MAX_ID = 128;
	private static final int WRAP_LENGTH = 12 + ContentKey.LENGTH + 16;
	private static final int MIN_PAYLOAD = 12 + 16;
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9+/=,_-]{1," + MAX_ID + "}");
	private static final SecureRandom RANDOM = new SecureRandom();

	private NoteEnvelope() {}

	/** 锁的种类，code 是写在文件里的一个字母。 */
	public enum Kind {
		USER('u'), FOLDER('f'), PASSWORD('p');

		final char code;

		Kind(char code) {
			this.code = code;
		}

		static Kind of(String code) {
			for (Kind kind : values()) {
				if (code.length() == 1 && code.charAt(0) == kind.code) {
					return kind;
				}
			}
			throw invalid();
		}
	}

	/** 一把锁：种类 + 标识。标识只能用 Base64 和 ",_-" 里的字符，最长 128。 */
	public record Lock(Kind kind, String id) {
		public Lock {
			Objects.requireNonNull(kind, "kind");
			if (id == null || !ID.matcher(id).matches()) {
				throw new IllegalArgumentException("锁的标识不合法");
			}
		}

		/** 玩家主密钥的锁。 */
		public static Lock user(String owner) {
			return new Lock(Kind.USER, owner);
		}
	}

	/** 正文是不是 MPAL2 密文：去掉首尾空白后以 PREFIX 开头。 */
	public static boolean isEnvelope(String body) {
		return body != null && body.strip().startsWith(PREFIX);
	}

	/**
	 * 新加密一篇笔记：用 key 加密正文，再用 wrappingKeys 里的每把锁各包一份 key。至少要有一把锁。
	 * wrappingKeys 的值是 32 字节包装钥匙（见 userWrappingKey、passwordWrappingKey），本方法不清零它们。
	 */
	public static String seal(UUID noteId, ContentKey key, String plaintext, Map<Lock, byte[]> wrappingKeys) {
		Objects.requireNonNull(noteId, "noteId");
		if (wrappingKeys.isEmpty() || wrappingKeys.size() > MAX_LOCKS) {
			throw new IllegalArgumentException("锁的数量不对");
		}
		byte[] secret = key.copy();
		try {
			Map<Lock, byte[]> wraps = new LinkedHashMap<>();
			wrappingKeys.forEach((lock, wrappingKey) -> wraps.put(lock, wrap(noteId, lock, wrappingKey, secret)));
			return serialize(wraps, sealBody(noteId, secret, plaintext));
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/** 这篇笔记有哪些锁（按文件里的顺序）。格式不对 → IllegalArgumentException。 */
	public static List<Lock> locks(String envelope) {
		return new ArrayList<>(parse(envelope).wraps().keySet());
	}

	/** 用一把锁的包装钥匙取出内容钥匙。没有这把锁、钥匙不对、被改过、格式不对 → WrongKeyException。 */
	public static ContentKey unlock(UUID noteId, String envelope, Lock lock, byte[] wrappingKey) throws WrongKeyException {
		Objects.requireNonNull(noteId, "noteId");
		Parsed parsed = parseOrWrongKey(envelope);
		byte[] wrapped = parsed.wraps().get(lock);
		if (wrapped == null) {
			throw NoteCipher.wrongKey();
		}
		byte[] secret = NoteCipher.open(wrappingKey, lockAad(noteId, lock), wrapped);
		try {
			if (secret.length != ContentKey.LENGTH) {
				throw NoteCipher.wrongKey();
			}
			return new ContentKey(secret);
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/**
	 * 用玩家主密钥取出内容钥匙：先试 preferred 这把 u 锁（自己的 UUID），再试其余 u 锁
	 * （换了账号、单人时代的 "host" 等）。都打不开 → WrongKeyException。
	 */
	public static ContentKey unlockAsUser(UUID noteId, String envelope, MasterKey master, String preferred)
			throws WrongKeyException {
		List<Lock> users = new ArrayList<>();
		for (Lock lock : parseOrWrongKey(envelope).wraps().keySet()) {
			if (lock.kind() == Kind.USER) {
				users.add(lock.id().equals(preferred) ? 0 : users.size(), lock);
			}
		}
		byte[] wrapping = userWrappingKey(master);
		try {
			for (Lock lock : users) {
				try {
					return unlock(noteId, envelope, lock, wrapping);
				} catch (WrongKeyException e) {
					// 试下一把
				}
			}
			throw NoteCipher.wrongKey();
		} finally {
			NoteCipher.wipe(wrapping);
		}
	}

	/** 解密正文。钥匙不对、被改过、格式不对 → WrongKeyException。 */
	public static String decrypt(UUID noteId, String envelope, ContentKey key) throws WrongKeyException {
		Objects.requireNonNull(noteId, "noteId");
		return openBody(noteId, parseOrWrongKey(envelope).payload(), key);
	}

	/** 换正文（编辑后保存），锁不变。key 必须能解开现有正文，否则 WrongKeyException。 */
	public static String withBody(UUID noteId, String envelope, ContentKey key, String plaintext) throws WrongKeyException {
		Parsed parsed = verified(noteId, envelope, key);
		byte[] secret = key.copy();
		try {
			return serialize(parsed.wraps(), sealBody(noteId, secret, plaintext));
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/** 加一把锁（同种类同标识的已有锁会被换掉）。key 必须能解开现有正文，否则 WrongKeyException。 */
	public static String withLock(UUID noteId, String envelope, ContentKey key, Lock lock, byte[] wrappingKey)
			throws WrongKeyException {
		Parsed parsed = verified(noteId, envelope, key);
		Map<Lock, byte[]> wraps = new LinkedHashMap<>(parsed.wraps());
		if (!wraps.containsKey(lock) && wraps.size() >= MAX_LOCKS) {
			throw new IllegalArgumentException("锁太多了");
		}
		byte[] secret = key.copy();
		try {
			wraps.put(lock, wrap(noteId, lock, wrappingKey, secret));
			return serialize(wraps, parsed.payload());
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	// ---- 包装钥匙 ----

	/** 玩家主密钥 → 包 USER 锁用的钥匙（HKDF 域分隔，不直接用主密钥）。调用方用完清零。 */
	public static byte[] userWrappingKey(MasterKey master) {
		byte[] secret = NoteCipher.copyKey(master);
		try {
			return hkdf(secret, "MPAL2|wrap|user");
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	/**
	 * 文件夹钥匙 → 包 FOLDER 锁用的钥匙。文件夹钥匙本身用和钥匙圈一样的格式（KeyRing）由文件夹自己的密码锁住，
	 * 和玩家的保险库密码无关，所以只有 FOLDER 锁、没有 USER 锁的笔记，保险库密码打不开。调用方用完清零。
	 */
	public static byte[] folderWrappingKey(MasterKey folderKey) {
		byte[] secret = NoteCipher.copyKey(folderKey);
		try {
			return hkdf(secret, "MPAL2|wrap|folder");
		} finally {
			NoteCipher.wipe(secret);
		}
	}

	// ---- 单篇阅读密码（p 锁） ----
	// 密码 → Argon2id（随机盐）→ 中间值，再经 HKDF 分成两样：
	// - 包装钥匙：只在客户端，用来锁内容钥匙
	// - 验证码：交给服务端核对"密码对不对"，对了才把密文发给对方。服务端只存验证码的 SHA-256（写在锁标识最后一段），
	//   拿着笔记文件的人也没法直接拿它去冒充
	// 锁标识 = "a2id,内存KiB,迭代次数,并行度,Base64(盐),Base64(SHA-256(验证码))"；
	// 前五段叫"题目"（challenge），服务端发给想读的人，让对方客户端照着算；最后一段绝不发出去（否则能离线猜密码不受限速）

	/** 新的单篇阅读密码锁：随机盐、默认参数。返回锁和它的包装钥匙（调用方用完清零）。 */
	public record PasswordLock(Lock lock, byte[] wrappingKey) {}

	/** 从阅读密码算出的两样东西。用完调用 wipe()。 */
	public record PasswordKeys(byte[] wrappingKey, byte[] verifier) {
		public void wipe() {
			NoteCipher.wipe(wrappingKey);
			NoteCipher.wipe(verifier);
		}
	}

	public static PasswordLock newPasswordLock(char[] password) {
		byte[] salt = new byte[PasswordKdf.SALT_LENGTH];
		RANDOM.nextBytes(salt);
		String challenge = "a2id," + PasswordKdf.MEMORY_KIB + "," + PasswordKdf.ITERATIONS + ","
				+ PasswordKdf.PARALLELISM + "," + Base64.getEncoder().encodeToString(salt);
		NoteCipher.wipe(salt);
		PasswordKeys keys = passwordKeys(challenge, password);
		try {
			Lock lock = new Lock(Kind.PASSWORD, challenge + "," + Base64.getEncoder().encodeToString(sha256(keys.verifier())));
			return new PasswordLock(lock, keys.wrappingKey().clone());
		} finally {
			keys.wipe();
		}
	}

	/** 密码锁的"题目"（前五段：参数和盐），可以发给想读的人。不是密码锁、格式不对 → IllegalArgumentException。 */
	public static String passwordChallenge(Lock lock) {
		String[] parts = passwordParts(lock);
		return String.join(",", parts[0], parts[1], parts[2], parts[3], parts[4]);
	}

	/**
	 * 按"题目"从密码算出包装钥匙和验证码（读的人、作者都用它）。题目格式不对、参数超范围 → IllegalArgumentException。
	 * 要跑一次 Argon2（约半秒），别在渲染线程上调用。
	 */
	public static PasswordKeys passwordKeys(String challenge, char[] password) {
		String[] parts = challenge == null ? new String[0] : challenge.split(",", -1);
		if (parts.length != 5 || !parts[0].equals("a2id")) {
			throw new IllegalArgumentException("密码锁参数不受支持");
		}
		int memory = number(parts[1]);
		int iterations = number(parts[2]);
		int parallelism = number(parts[3]);
		byte[] salt = decode(parts[4], PasswordKdf.SALT_LENGTH);
		byte[] secret = null;
		try {
			// derive 会先检查参数范围，被改坏的参数不会把内存吃光
			secret = PasswordKdf.derive(password, salt, memory, iterations, parallelism);
			return new PasswordKeys(hkdf(secret, "MPAL2|wrap|password"), hkdf(secret, "MPAL2|verify|password"));
		} finally {
			NoteCipher.wipe(salt);
			NoteCipher.wipe(secret);
		}
	}

	/**
	 * 服务端发来的题目够不够强：参数不低于现在的默认值（m ≥ 64MiB、t ≥ 3）。读者客户端只照着强题目算——
	 * 否则恶意服务端可以发一道极弱的题，骗玩家输入密码，拿到能高速离线猜的验证码。格式不对 → false。
	 */
	public static boolean challengeIsStrong(String challenge) {
		String[] parts = challenge == null ? new String[0] : challenge.split(",", -1);
		if (parts.length != 5 || !parts[0].equals("a2id")) {
			return false;
		}
		try {
			int memory = number(parts[1]);
			int iterations = number(parts[2]);
			int parallelism = number(parts[3]);
			decode(parts[4], PasswordKdf.SALT_LENGTH);
			return PasswordKdf.check(memory, iterations, parallelism)
					&& memory >= PasswordKdf.MEMORY_KIB && iterations >= PasswordKdf.ITERATIONS;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** 按密码锁算出包装钥匙（作者自己用，比如测试或以后换锁）。调用方用完清零。 */
	public static byte[] passwordWrappingKey(Lock lock, char[] password) {
		PasswordKeys keys = passwordKeys(passwordChallenge(lock), password);
		NoteCipher.wipe(keys.verifier());
		return keys.wrappingKey();
	}

	/** 服务端核对验证码（常数时间比较）。格式不对、不是密码锁 → false。 */
	public static boolean checkPassword(Lock lock, byte[] verifier) {
		if (verifier == null || verifier.length != 32) {
			return false;
		}
		try {
			byte[] expected = decode(passwordParts(lock)[5], 32);
			return java.security.MessageDigest.isEqual(expected, sha256(verifier));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** 这篇笔记的阅读密码锁；没有就是 empty。格式不对 → IllegalArgumentException。 */
	public static java.util.Optional<Lock> passwordLock(String envelope) {
		return locks(envelope).stream().filter(lock -> lock.kind() == Kind.PASSWORD).findFirst();
	}

	/** 有几把阅读密码锁（每篇最多该有一把）。格式不对 → IllegalArgumentException。 */
	public static long passwordLockCount(String envelope) {
		return locks(envelope).stream().filter(lock -> lock.kind() == Kind.PASSWORD).count();
	}

	private static String[] passwordParts(Lock lock) {
		if (lock.kind() != Kind.PASSWORD) {
			throw new IllegalArgumentException("不是密码锁");
		}
		String[] parts = lock.id().split(",", -1);
		if (parts.length != 6 || !parts[0].equals("a2id")) {
			throw new IllegalArgumentException("密码锁参数不受支持");
		}
		return parts;
	}

	private static byte[] sha256(byte[] data) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 SHA-256");
		}
	}

	// ---- 内部 ----

	private record Parsed(Map<Lock, byte[]> wraps, byte[] payload) {}

	private static Parsed verified(UUID noteId, String envelope, ContentKey key) throws WrongKeyException {
		Objects.requireNonNull(noteId, "noteId");
		Parsed parsed = parseOrWrongKey(envelope);
		// 钥匙对不上现有正文就拒绝：不然一篇笔记里会混进两把内容钥匙。解出来的明文只用来验证，马上清零
		byte[] secret = key.copy();
		byte[] plain = null;
		try {
			plain = NoteCipher.open(secret, bodyAad(noteId), parsed.payload());
		} finally {
			NoteCipher.wipe(secret);
			NoteCipher.wipe(plain);
		}
		return parsed;
	}

	private static byte[] wrap(UUID noteId, Lock lock, byte[] wrappingKey, byte[] secret) {
		if (wrappingKey == null || wrappingKey.length != 32) {
			throw new IllegalArgumentException("包装钥匙必须是 32 字节");
		}
		return NoteCipher.seal(wrappingKey, lockAad(noteId, lock), secret);
	}

	private static byte[] sealBody(UUID noteId, byte[] secret, String plaintext) {
		byte[] plain = plaintext.getBytes(StandardCharsets.UTF_8);
		try {
			return NoteCipher.seal(secret, bodyAad(noteId), plain);
		} finally {
			NoteCipher.wipe(plain);
		}
	}

	private static String openBody(UUID noteId, byte[] payload, ContentKey key) throws WrongKeyException {
		byte[] secret = key.copy();
		byte[] plain = null;
		try {
			plain = NoteCipher.open(secret, bodyAad(noteId), payload);
			return new String(plain, StandardCharsets.UTF_8);
		} finally {
			NoteCipher.wipe(secret);
			NoteCipher.wipe(plain);
		}
	}

	private static byte[] bodyAad(UUID noteId) {
		return ("MPAL2|body|" + noteId).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] lockAad(UUID noteId, Lock lock) {
		return ("MPAL2|lock|" + noteId + "|" + lock.kind().code + "|" + lock.id()).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] hkdf(byte[] ikm, String info) {
		try {
			return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract().addIKM(ikm)
					.thenExpand(info.getBytes(StandardCharsets.UTF_8), 32));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法执行 HKDF-SHA-256");
		}
	}

	private static String serialize(Map<Lock, byte[]> wraps, byte[] payload) {
		StringBuilder out = new StringBuilder(PREFIX);
		boolean first = true;
		for (Map.Entry<Lock, byte[]> entry : wraps.entrySet()) {
			if (!first) {
				out.append(';');
			}
			first = false;
			out.append(entry.getKey().kind().code).append('.').append(entry.getKey().id()).append('.')
					.append(Base64.getEncoder().encodeToString(entry.getValue()));
		}
		return out.append('|').append(Base64.getEncoder().encodeToString(payload)).toString();
	}

	private static Parsed parseOrWrongKey(String envelope) throws WrongKeyException {
		try {
			return parse(envelope);
		} catch (IllegalArgumentException e) {
			throw NoteCipher.wrongKey();
		}
	}

	// 严格解析：重复的锁、未知种类、非规范 Base64、长度不对一律拒绝
	private static Parsed parse(String envelope) {
		if (!isEnvelope(envelope)) {
			throw invalid();
		}
		String text = envelope.strip().substring(PREFIX.length());
		int bar = text.indexOf('|');
		if (bar <= 0 || bar != text.lastIndexOf('|')) {
			throw invalid();
		}
		Map<Lock, byte[]> wraps = new LinkedHashMap<>();
		for (String item : text.substring(0, bar).split(";", -1)) {
			String[] parts = item.split("\\.", -1);
			if (parts.length != 3) {
				throw invalid();
			}
			Lock lock;
			try {
				lock = new Lock(Kind.of(parts[0]), parts[1]);
			} catch (IllegalArgumentException e) {
				throw invalid();
			}
			if (wraps.putIfAbsent(lock, decode(parts[2], WRAP_LENGTH)) != null || wraps.size() > MAX_LOCKS) {
				throw invalid();
			}
		}
		byte[] payload = decode(text.substring(bar + 1), -1);
		if (payload.length < MIN_PAYLOAD) {
			throw invalid();
		}
		return new Parsed(wraps, payload);
	}

	// length < 0：不限长度
	private static byte[] decode(String text, int length) {
		try {
			byte[] bytes = Base64.getDecoder().decode(text);
			if ((length >= 0 && bytes.length != length) || !Base64.getEncoder().encodeToString(bytes).equals(text)) {
				throw invalid();
			}
			return bytes;
		} catch (IllegalArgumentException e) {
			throw invalid();
		}
	}

	private static int number(String text) {
		if (!text.matches("[1-9][0-9]{0,5}")) {
			throw new IllegalArgumentException("密码锁参数不受支持");
		}
		return Integer.parseInt(text);
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("MPAL2 格式不对");
	}
}
