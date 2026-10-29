package com.memorypalace.core.vault;

import com.memorypalace.core.crypto.ContentKey;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 不开游戏解密笔记（配套小工具 DecryptTool 用）：拿密码或恢复密钥，把一个笔记库文件夹里的笔记
 * 解成明文、原样的目录结构另存一份。原文件一个字节都不动。
 * <p>
 * 能用的钥匙：笔记库里 .palace/keyring、.palace/keyrings/* 这些钥匙圈（自己的主密钥），各文件夹里的 .palace-lock
 * （文件夹独立密码），都用输入的同一个密码 / 恢复密钥试着打开；单篇阅读密码锁也拿这个密码试。
 * 解不开的笔记不写出来，记在结果里（多半是别人的笔记，或者用了另一个密码）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class VaultDecryptor {
	/** 收件箱里解不开的随手记换成这一行。 */
	public static final String UNREADABLE_LINE = "[无法解密此条记录]";

	/**
	 * 结果。
	 * @param output    写到了哪个文件夹
	 * @param decrypted 解密了几篇
	 * @param plain     本来就是明文、照抄的几篇
	 * @param failed    解不开的（相对路径）
	 * @param keys      密码 / 恢复密钥打开了几个钥匙圈和文件夹锁；0 = 密码多半输错了
	 */
	public record Result(Path output, int decrypted, int plain, List<String> failed, int keys) {}

	private final List<MasterKey> masters = new ArrayList<>();
	private final List<byte[][]> boxes = new ArrayList<>(); // {私钥, 公钥}
	private final Map<String, MasterKey> folders = new HashMap<>(); // 文件夹钥匙 ID → 钥匙
	private final char[] secret;

	private VaultDecryptor(char[] secret) {
		this.secret = secret;
	}

	/**
	 * 解密 vault 这个文件夹（最好是整个 memory-palace 文件夹：自己的钥匙圈在它的 .palace 里），写到 output。
	 * output 必须还不存在（不覆盖任何东西）。secret 是密码或恢复密钥，调用方用完自己清零。
	 */
	public static Result decrypt(Path vault, Path output, char[] secret) throws IOException {
		if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("输出文件夹已存在：" + output);
		}
		VaultDecryptor d = new VaultDecryptor(secret);
		try {
			d.loadKeys(vault);
			int keys = d.masters.size() + d.folders.size();
			int decrypted = 0;
			int plain = 0;
			List<String> failed = new ArrayList<>();
			Files.createDirectories(output);
			for (Path file : notes(vault)) {
				String rel = vault.relativize(file).toString().replace('\\', '/');
				String text = Files.readString(file, StandardCharsets.UTF_8);
				String body = FrontMatter.parse(text).body();
				String out;
				if (NoteCipher.isEncrypted(body) || NoteEnvelope.isEnvelope(body) || InboxCipher.hasSealedLines(body)) {
					String opened = d.open(FrontMatter.parse(text).id(), body);
					if (opened == null) {
						failed.add(rel);
						continue;
					}
					// front matter（palace-id 等）原样留着，只换正文
					out = text.substring(0, text.length() - body.length()) + opened;
					decrypted++;
				} else {
					out = text;
					plain++;
				}
				Path target = output.resolve(rel);
				Files.createDirectories(target.getParent());
				Files.writeString(target, out, StandardCharsets.UTF_8);
			}
			return new Result(output, decrypted, plain, failed, keys);
		} finally {
			d.masters.forEach(MasterKey::destroy);
			d.folders.values().forEach(MasterKey::destroy);
			d.boxes.forEach(pair -> wipe(pair[0]));
		}
	}

	/** vault 里所有 .md 笔记（不含 .palace 里的历史版本、回收站），按路径排序。 */
	private static List<Path> notes(Path vault) throws IOException {
		try (Stream<Path> files = Files.walk(vault)) {
			return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
					.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
					.filter(p -> !vault.relativize(p).startsWith(".palace"))
					.sorted()
					.toList();
		}
	}

	private void loadKeys(Path vault) throws IOException {
		Path palace = vault.resolve(".palace");
		List<Path> keyrings = new ArrayList<>();
		keyrings.add(palace.resolve("keyring"));
		Path others = palace.resolve("keyrings");
		if (Files.isDirectory(others)) {
			try (Stream<Path> files = Files.list(others)) {
				files.sorted().forEach(keyrings::add);
			}
		}
		for (Path file : keyrings) {
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			String content = Files.readString(file, StandardCharsets.UTF_8);
			MasterKey key = unlock(content);
			if (key == null) {
				continue;
			}
			masters.add(key);
			try {
				if (KeyRing.hasBox(content)) {
					boxes.add(new byte[][] {KeyRing.boxPrivate(content, key), KeyRing.boxPublic(content)});
				}
			} catch (WrongKeyException | IllegalArgumentException e) {
				// 收件箱钥匙坏了：随手记解不开，其他照常
			}
		}
		try (Stream<Path> files = Files.walk(vault)) {
			for (Path lock : files.filter(p -> p.getFileName().toString().equals(FolderLocks.FILE)).toList()) {
				String content = Files.readString(lock, StandardCharsets.UTF_8);
				MasterKey key = unlock(content);
				if (key == null) {
					continue;
				}
				try {
					String id = KeyRing.folderId(content);
					if (id != null) {
						folders.put(id, key);
						continue;
					}
				} catch (IllegalArgumentException e) {
					// 落到下面销毁
				}
				key.destroy();
			}
		}
	}

	/** 输入的可能是密码，也可能是恢复密钥：都试一下。打不开 → null。 */
	private MasterKey unlock(String content) {
		try {
			return KeyRing.unlock(content, secret);
		} catch (WrongKeyException | IllegalArgumentException e) {
			// 再当恢复密钥试
		}
		try {
			return KeyRing.unlockWithRecovery(content, new String(secret));
		} catch (WrongKeyException | IllegalArgumentException e) {
			return null;
		}
	}

	private static void wipe(byte[] bytes) {
		if (bytes != null) {
			java.util.Arrays.fill(bytes, (byte) 0);
		}
	}

	/** 解开一篇的正文；解不开 → null。 */
	private String open(UUID id, String body) {
		if (InboxCipher.hasSealedLines(body) && !NoteCipher.isEncrypted(body) && !NoteEnvelope.isEnvelope(body)) {
			// 收件箱：一行一条随手记，用收件箱私钥解
			for (byte[][] box : boxes) {
				String opened = InboxCipher.openAll(box[0], box[1], body, UNREADABLE_LINE);
				if (!opened.contains(UNREADABLE_LINE) || box == boxes.getLast()) {
					return opened;
				}
			}
			return null;
		}
		if (id == null) {
			return null; // 密文绑定笔记 ID，没有 palace-id 解不了
		}
		if (NoteCipher.isEncrypted(body)) {
			for (MasterKey master : masters) {
				try {
					return NoteCipher.decrypt(master, id, body);
				} catch (WrongKeyException e) {
					// 下一把
				}
			}
			return null;
		}
		try {
			ContentKey key = contentKey(id, body);
			if (key == null) {
				return null;
			}
			try {
				return NoteEnvelope.decrypt(id, body, key);
			} finally {
				key.destroy();
			}
		} catch (WrongKeyException | IllegalArgumentException e) {
			return null;
		}
	}

	private ContentKey contentKey(UUID id, String envelope) {
		for (MasterKey master : masters) {
			try {
				return NoteEnvelope.unlockAsUser(id, envelope, master, "host");
			} catch (WrongKeyException | IllegalArgumentException e) {
				// 下一把
			}
		}
		for (NoteEnvelope.Lock lock : NoteEnvelope.locks(envelope)) {
			byte[] wrapping = null;
			try {
				if (lock.kind() == NoteEnvelope.Kind.FOLDER && folders.containsKey(lock.id())) {
					wrapping = NoteEnvelope.folderWrappingKey(folders.get(lock.id()));
				} else if (lock.kind() == NoteEnvelope.Kind.PASSWORD) {
					wrapping = NoteEnvelope.passwordWrappingKey(lock, secret);
				} else {
					continue;
				}
				return NoteEnvelope.unlock(id, envelope, lock, wrapping);
			} catch (WrongKeyException | IllegalArgumentException e) {
				// 下一把
			} finally {
				wipe(wrapping);
			}
		}
		return null;
	}
}
