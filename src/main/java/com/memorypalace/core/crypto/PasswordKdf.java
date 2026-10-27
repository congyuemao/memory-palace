package com.memorypalace.core.crypto;

import com.password4j.Argon2Function;
import com.password4j.Hash;
import com.password4j.types.Argon2;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 密码 → 32 字节钥匙（Argon2id，password4j）。钥匙圈、文件夹密码、单篇阅读密码共用。
 * 参数随结果一起存下来（以后改默认值也能打开旧数据），读取时先用 {@link #check} 校验范围再算，
 * 防止被改坏的参数把内存吃光。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class PasswordKdf {
	/** 新建时的默认参数：m=64MiB、t=3、p=1。 */
	public static final int MEMORY_KIB = 65536;
	public static final int ITERATIONS = 3;
	public static final int PARALLELISM = 1;
	public static final int SALT_LENGTH = 16;

	private PasswordKdf() {}

	/** 参数在不在允许范围内：8p ≤ m ≤ 262144 KiB、1 ≤ t ≤ 10、1 ≤ p ≤ 4，且 m*t ≤ 786432。 */
	public static boolean check(int memoryKiB, int iterations, int parallelism) {
		return iterations >= 1 && iterations <= 10 && parallelism >= 1 && parallelism <= 4
				&& memoryKiB >= 8 * parallelism && memoryKiB <= 262144 && (long) memoryKiB * iterations <= 786432;
	}

	/**
	 * 算出 32 字节钥匙。不把密码转成不可清零的 String；含不完整 UTF-16 的密码 → IllegalArgumentException。
	 * 参数不在范围内 → IllegalArgumentException。调用方用完要清零返回值。
	 */
	public static byte[] derive(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
		if (password == null) {
			throw new IllegalArgumentException("密码不能为空");
		}
		if (!check(memoryKiB, iterations, parallelism) || salt == null || salt.length != SALT_LENGTH) {
			throw new IllegalArgumentException("密码派生参数不受支持");
		}
		char[] copy = password.clone();
		ByteBuffer encoded = null;
		byte[] bytes = null;
		Hash hash = null;
		try {
			encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(copy));
			bytes = new byte[encoded.remaining()];
			encoded.get(bytes);
			Argon2Function argon2 = Argon2Function.getInstance(memoryKiB, iterations, parallelism,
					MasterKey.LENGTH, Argon2.ID, Argon2Function.ARGON2_VERSION_13);
			hash = argon2.hash(bytes, salt);
			return hash.getBytes().clone();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("密码含无效字符");
		} finally {
			Arrays.fill(copy, '\0');
			if (encoded != null && encoded.hasArray()) {
				NoteCipher.wipe(encoded.array());
			}
			NoteCipher.wipe(bytes);
			if (hash != null) {
				// password4j 还生成了 PHC 编码副本，和原始派生值一起清零
				NoteCipher.wipe(hash.getBytes());
				NoteCipher.wipe(hash.getResultAsBytes());
			}
		}
	}
}
