package com.memorypalace.client;

import com.memorypalace.MemoryPalace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 用系统默认程序打开笔记文件（.md 关联的是 Obsidian/Typora/记事本，就用它打开）。
 * 26.x 里原版没有现成的工具方法了，这里按系统分别调用。
 */
final class ExternalEditor {
	private ExternalEditor() {}

	static void open(Path file) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String path = file.toAbsolutePath().toString();
		ProcessBuilder pb;
		if (os.contains("win")) {
			// rundll32 按文件关联打开，不经过 cmd，路径里有空格、中文、& 都安全
			pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", path);
		} else if (os.contains("mac")) {
			pb = new ProcessBuilder("open", path);
		} else {
			pb = new ProcessBuilder("xdg-open", path);
		}
		start(pb, path);
	}

	/** 在资源管理器（访达、文件管理器）里显示：是文件就打开所在文件夹并选中它，是文件夹就打开它。 */
	static void reveal(Path target) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Path abs = target.toAbsolutePath();
		boolean file = java.nio.file.Files.isRegularFile(abs);
		String path = abs.toString();
		ProcessBuilder pb;
		if (os.contains("win")) {
			pb = file ? new ProcessBuilder("explorer.exe", "/select," + path) : new ProcessBuilder("explorer.exe", path);
		} else if (os.contains("mac")) {
			pb = file ? new ProcessBuilder("open", "-R", path) : new ProcessBuilder("open", path);
		} else {
			// xdg-open 没有"选中文件"，打开所在文件夹
			pb = new ProcessBuilder("xdg-open", (file ? abs.getParent() : abs).toString());
		}
		start(pb, path);
	}

	private static void start(ProcessBuilder pb, String path) {
		// 在后台线程启动，别卡住游戏画面
		Thread.ofVirtual().start(() -> {
			try {
				pb.start();
			} catch (IOException e) {
				MemoryPalace.LOGGER.warn("无法用外部程序打开 {}", path, e);
			}
		});
	}
}
