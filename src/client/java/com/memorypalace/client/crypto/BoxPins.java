package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 防公钥调包：钥匙圈存在服务端，恶意服务器可以把收件箱公钥换成它自己的，骗客户端用它的公钥加密随手记。
 * 所以客户端在本机记住"自己在这个服务器上的收件箱公钥指纹"（第一次见到时记下），之后每次随手记都核对。
 * 存在 .minecraft/config/memorypalace-pins.properties，按"服务器地址|玩家 UUID"区分。
 * 单人游戏和自己当房主的局域网：服务器就是自己，没有这个风险，不记也不核对。只在渲染线程使用。
 */
public final class BoxPins {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("memorypalace-pins.properties");

	/** 核对结果。 */
	public enum Result {
		/** 自己的世界，不用核对。 */
		LOCAL,
		/** 和记住的一样。 */
		MATCH,
		/** 第一次见到，已经记下。 */
		FIRST_SEEN,
		/** 和记住的不一样：可能是被调包了，也可能是重设过钥匙圈、换了电脑、本地记录被清掉。 */
		MISMATCH
	}

	private BoxPins() {}

	/** 当前服务器 + 玩家的记录名；单人游戏或自己当房主时为 null。 */
	private static String slot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.hasSingleplayerServer()) {
			return null;
		}
		ServerData server = mc.getCurrentServer();
		String address = server == null || server.ip == null ? "unknown" : server.ip.strip().toLowerCase(java.util.Locale.ROOT);
		return address + "|" + mc.player.getUUID();
	}

	/** 核对这个指纹；第一次见到就记下来。 */
	public static Result check(String fingerprint) {
		String slot = slot();
		if (slot == null) {
			return Result.LOCAL;
		}
		Properties pins = load();
		String pinned = pins.getProperty(slot);
		if (pinned == null) {
			pins.setProperty(slot, fingerprint);
			save(pins);
			return Result.FIRST_SEEN;
		}
		return pinned.equals(fingerprint) ? Result.MATCH : Result.MISMATCH;
	}

	/** 当前服务器上记住的指纹；没记过或者是自己的世界时为 null。 */
	public static String pinned() {
		String slot = slot();
		return slot == null ? null : load().getProperty(slot);
	}

	/** 改成信任新的指纹（设置里"信任新公钥"，玩家看过新旧指纹并确认之后）。 */
	public static void trust(String fingerprint) {
		String slot = slot();
		if (slot == null) {
			return;
		}
		Properties pins = load();
		pins.setProperty(slot, fingerprint);
		save(pins);
	}

	private static Properties load() {
		Properties pins = new Properties();
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				pins.load(r);
			} catch (IOException | IllegalArgumentException e) {
				MemoryPalace.LOGGER.warn("读取收件箱公钥记录失败", e);
			}
		}
		return pins;
	}

	private static void save(Properties pins) {
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = Files.createTempFile(FILE.getParent(), ".memorypalace-pins-", ".tmp");
			try {
				try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
					pins.store(w, "Memory Palace: inbox public key fingerprints (server|player -> fingerprint)");
				}
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("保存收件箱公钥记录失败", e);
		}
	}
}
