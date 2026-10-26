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
