package com.memorypalace.core.crypto;

import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.HKDFParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 收件箱的"投信口"：随手记（按 K）不用输密码也能加密——用收件人的公钥加密，只有钥匙圈里的私钥能解开。
 * 每一条单独加密，一行一条：
 * <pre>MPALBOX1:&lt;Base64(临时公钥 X.509 编码 44 字节 || nonce 12 || AES-256-GCM 密文和 16 字节 tag)&gt;</pre>
 * 流程（ECIES 式）：每条生成一对一次性 X25519 密钥 → 和收件人公钥算出共享秘密 →
 * HKDF-SHA256（salt = 临时公钥 || 收件人公钥，info = "MPALBOX1"）→ AES-256-GCM（AAD = "MPALBOX1"）。
 * 临时私钥用完就丢，所以加密的一方（包括服务端）事后也解不开。全部用 JDK 自带的 X25519、HKDF、AES-GCM，不加依赖。
 * 公钥、私钥都用标准编码（公钥 X.509，私钥 PKCS#8）的字节传来传去。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class InboxCipher {
	public static final String PREFIX = "MPALBOX1:";
	/** 一条密文最长这么多字符（随手记本身很短；挡住被塞进超长的行）。 */
	public static final int MAX_SEALED_CHARS = 8192;
	private static final int PUBLIC_LENGTH = 44;
	private static final byte[] INFO = "MPALBOX1".getBytes(StandardCharsets.UTF_8);
	private static final Pattern SEALED = Pattern.compile("MPALBOX1:[A-Za-z0-9+/]+={0,2}");

	private InboxCipher() {}

	/** 一对收件箱钥匙：公钥（X.509 编码）和私钥（PKCS#8 编码）。私钥用完要清零。 */
	public record BoxKeys(byte[] publicKey, byte[] privateKey) {}

	/** 新生成一对 X25519 钥匙。 */
	public static BoxKeys generate() {
		try {
			KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
			return new BoxKeys(pair.getPublic().getEncoded(), pair.getPrivate().getEncoded());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("无法生成 X25519 钥匙");
		}
	}

	/** 这一行是不是一条收件箱密文（去掉首尾空白后整行都是）。 */
	public static boolean isSealed(String line) {
		if (line == null) {
			return false;
		}
		String s = line.strip();
		return s.length() <= MAX_SEALED_CHARS && SEALED.matcher(s).matches();
	}

	/** 正文里有没有收件箱密文行。 */
	public static boolean hasSealedLines(String body) {
		return body != null && body.lines().anyMatch(InboxCipher::isSealed);
	}

	/** 用收件人的公钥加密一行。公钥不合法 → IllegalArgumentException。 */
	public static String seal(byte[] recipientPublic, String line) {
		PublicKey recipient = publicKey(recipientPublic);
		BoxKeys ephemeral = generate();
		byte[] key = null;
		byte[] plain = line.getBytes(StandardCharsets.UTF_8);
		try {
			key = derive(privateKey(ephemeral.privateKey()), recipient, ephemeral.publicKey(), recipient.getEncoded());
			byte[] sealed = NoteCipher.seal(key, INFO, plain);
			byte[] packed = Arrays.copyOf(ephemeral.publicKey(), PUBLIC_LENGTH + sealed.length);
			System.arraycopy(sealed, 0, packed, PUBLIC_LENGTH, sealed.length);
			String out = PREFIX + Base64.getEncoder().encodeToString(packed);
			if (out.length() > MAX_SEALED_CHARS) {
				throw new IllegalArgumentException("内容太长");
			}
			return out;
		} finally {
			NoteCipher.wipe(key);
			NoteCipher.wipe(plain);
			NoteCipher.wipe(ephemeral.privateKey());
		}
	}

	/** 用私钥解开一行。不是密文、钥匙不对、被改过 → WrongKeyException。 */
	public static String open(byte[] privateKey, byte[] publicKey, String sealed) throws WrongKeyException {
		if (!isSealed(sealed)) {
			throw NoteCipher.wrongKey();
		}
		byte[] packed;
		String text = sealed.strip().substring(PREFIX.length());
		try {
			packed = Base64.getDecoder().decode(text);
		} catch (IllegalArgumentException e) {
			throw NoteCipher.wrongKey();
		}
		// 只认规范编码（缺填充、填充位不为零的写法一律拒绝）：同一条密文只有一种合法的写法
		if (!Base64.getEncoder().encodeToString(packed).equals(text)) {
			NoteCipher.wipe(packed);
			throw NoteCipher.wrongKey();
		}
		if (packed.length < PUBLIC_LENGTH + 12 + 16) {
			throw NoteCipher.wrongKey();
		}
		byte[] key = null;
		byte[] plain = null;
		try {
			byte[] ephemeralPublic = Arrays.copyOf(packed, PUBLIC_LENGTH);
			PublicKey ephemeral;
			try {
				ephemeral = publicKey(ephemeralPublic);
			} catch (IllegalArgumentException e) {
				throw NoteCipher.wrongKey();
			}
			try {
				key = derive(privateKey(privateKey), ephemeral, ephemeralPublic, publicKey);
			} catch (IllegalArgumentException e) {
				throw NoteCipher.wrongKey(); // 被构造的临时公钥（低阶点）等
			}
			plain = NoteCipher.open(key, INFO, Arrays.copyOfRange(packed, PUBLIC_LENGTH, packed.length));
			return new String(plain, StandardCharsets.UTF_8);
		} finally {
			NoteCipher.wipe(key);
			NoteCipher.wipe(plain);
			NoteCipher.wipe(packed);
		}
	}

	/**
	 * 把正文里所有密文行解开，其他行原样保留。解不开的行换成 unreadable（比如"[这一条无法解密]"）。
	 * 用来在客户端显示收件箱。
	 */
	public static String openAll(byte[] privateKey, byte[] publicKey, String body, String unreadable) {
		StringBuilder out = new StringBuilder();
		String[] lines = body.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (i > 0) {
				out.append('\n');
			}
			if (isSealed(line)) {
				try {
					out.append(open(privateKey, publicKey, line));
				} catch (WrongKeyException e) {
					out.append(unreadable);
				}
				// Windows 换行（\r\n）：密文行解开后把行尾的 \r 留着，换行风格不变
				if (line.endsWith("\r")) {
					out.append('\r');
				}
			} else {
				out.append(line);
			}
		}
		return out.toString();
	}

	/**
	 * 公钥指纹：SHA-256 的前 16 字节，每 4 个十六进制字符一组用 "-" 分隔，给人核对（"防公钥调包"）。
	 * 公钥不合法 → IllegalArgumentException。
	 */
	public static String fingerprint(byte[] publicKey) {
		publicKey(publicKey);
		try {
			String hex = HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(publicKey), 16));
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < hex.length(); i += 4) {
				if (i > 0) {
					out.append('-');
				}
				out.append(hex, i, i + 4);
			}
			return out.toString();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("运行环境不支持 SHA-256");
		}
	}

	// 共享秘密 → AES 钥匙。共享秘密全零说明对方公钥是低阶点（被构造的），拒绝
	private static byte[] derive(PrivateKey own, PublicKey other, byte[] ephemeralPublic, byte[] recipientPublic)
			throws IllegalArgumentException {
		byte[] shared = null;
		byte[] salt = null;
		try {
			KeyAgreement agreement = KeyAgreement.getInstance("X25519");
			agreement.init(own);
			agreement.doPhase(other, true);
			shared = agreement.generateSecret();
			boolean allZero = true;
			for (byte b : shared) {
				allZero &= b == 0;
			}
			if (allZero) {
				throw new IllegalArgumentException("公钥不合法");
			}
			salt = new byte[ephemeralPublic.length + recipientPublic.length];
			System.arraycopy(ephemeralPublic, 0, salt, 0, ephemeralPublic.length);
			System.arraycopy(recipientPublic, 0, salt, ephemeralPublic.length, recipientPublic.length);
			return KDF.getInstance("HKDF-SHA256").deriveData(HKDFParameterSpec.ofExtract()
					.addIKM(shared).addSalt(salt).thenExpand(INFO, 32));
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("无法计算共享秘密");
		} finally {
			NoteCipher.wipe(shared);
			NoteCipher.wipe(salt);
		}
	}

	static PublicKey publicKey(byte[] encoded) {
		if (encoded == null || encoded.length != PUBLIC_LENGTH) {
			throw new IllegalArgumentException("公钥不合法");
		}
		try {
			return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IllegalArgumentException("公钥不合法");
		}
	}

	private static PrivateKey privateKey(byte[] encoded) {
		try {
			return KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IllegalArgumentException("私钥不合法");
		}
	}
}
