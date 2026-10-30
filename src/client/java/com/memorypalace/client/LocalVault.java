package com.memorypalace.client;

import com.memorypalace.core.vault.Vault;
import com.memorypalace.core.vault.VaultBackup;
import com.memorypalace.mc.PalaceServer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 客户端直接访问本机的笔记库和备份：只有单人游戏、自己开的局域网时可用（服务端就在这个进程里，文件在本机硬盘上）。
 * 客户端代码只通过这里碰服务端的东西；别人的服务器上全部不可用。回调都在渲染线程上调用。
 */
final class LocalVault {
	private LocalVault() {}

	private static Optional<Vault> vault() {
		return Minecraft.getInstance().hasSingleplayerServer() ? Optional.ofNullable(PalaceServer.vault()) : Optional.empty();
	}

	/** 本机能不能直接访问笔记库。 */
	static boolean available() {
		return vault().isPresent();
	}

	static Optional<Path> root() {
		return vault().map(Vault::root);
	}

	/** 这篇笔记的文件。 */
	static Optional<Path> notePath(UUID id) {
		return vault().flatMap(v -> v.pathOf(id));
	}

	/** 当前世界的备份文件夹。 */
	static Optional<Path> backupDir() {
		return vault().isPresent() ? Optional.ofNullable(PalaceServer.backupDir()) : Optional.empty();
	}

	/** 当前世界的备份，从旧到新。 */
	static List<VaultBackup.Snapshot> snapshots() throws IOException {
		Optional<Path> dir = backupDir();
		return dir.isPresent() ? VaultBackup.snapshots(dir.get()) : List.of();
	}

	/** 立即备份；done(true) = 成功或内容没变。 */
	static void backupNow(Consumer<Boolean> done) {
		if (!available()) {
			done.accept(false);
			return;
		}
		PalaceServer.backupNow(ok -> Minecraft.getInstance().execute(() -> done.accept(ok)));
	}

	/** 把笔记库恢复成某一次备份（见 PalaceServer.restoreBackup）。 */
	static void restore(String snapshotId, Consumer<VaultBackup.RestoredInPlace> done, Consumer<String> failed) {
		if (!available()) {
			failed.accept("no vault");
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		PalaceServer.restoreBackup(snapshotId, result -> mc.execute(() -> done.accept(result)), error -> mc.execute(() -> failed.accept(error)));
	}

	/** path 相对笔记库根目录的写法（"/" 分隔）；不在笔记库里时原样返回。 */
	static String relative(Path path) {
		return root().filter(path::startsWith).map(r -> r.relativize(path).toString().replace('\\', '/')).orElse(path.toString());
	}
}
