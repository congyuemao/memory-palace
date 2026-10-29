package com.memorypalace.client;

import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.core.vault.VaultBackup;
import com.memorypalace.mc.PalaceServer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 浏览当前世界的备份（新的在前），选一份恢复到笔记库。只在单人游戏、自己开的局域网里能打开（备份在本机硬盘上）。
 * 恢复前服务端会先备份一次当前内容；被替换、多出来的文件移进 .palace/trash，所以恢复错了还能再恢复回来。
 */
public class BackupScreen extends Screen {
	private static final int MAX_PER_PAGE = 8;
	private static final int ROW = 22;
	private static final int TOP = 36;
	private static final int WIDTH = 260;
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Screen parent;
	private List<VaultBackup.Snapshot> snapshots = List.of(); // 新的在前
	private int page;
	private boolean busy;
	private Component status = Component.empty();

	public BackupScreen(Screen parent) {
		super(Component.translatable("screen.memorypalace.backups_title"));
		this.parent = parent;
	}

	/** 底下三个按钮贴着窗口底边；上面放页码和提示两行字，剩下的高度放几条算几条（窗口矮时少放几条）。 */
	private int buttonsY() {
		return this.height - 28;
	}

	private int perPage() {
		return Math.clamp((buttonsY() - 30 - TOP) / ROW, 1, MAX_PER_PAGE);
	}

	@Override
	protected void init() {
		load();
		int x = this.width / 2 - WIDTH / 2;
		int perPage = perPage();
		int from = page * perPage;
		for (int i = from; i < Math.min(snapshots.size(), from + perPage); i++) {
			VaultBackup.Snapshot snapshot = snapshots.get(i);
			Button restore = addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.backup_restore"),
					b -> confirm(snapshot)).bounds(x + WIDTH - 60, TOP + (i - from) * ROW, 60, 20).build());
			restore.active = !busy;
		}
		int bottom = buttonsY();
		Button prev = addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.backup_prev"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(x, bottom, 60, 20).build());
		Button next = addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.backup_next"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(x + WIDTH - 60, bottom, 60, 20).build());
		prev.active = page > 0;
		next.active = (page + 1) * perPage < snapshots.size();
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(this.width / 2 - 50, bottom, 100, 20).build());
	}

	/** 读出当前世界的备份列表（新的在前）。 */
	private void load() {
		Path dir = PalaceServer.backupDir();
		List<VaultBackup.Snapshot> list = new ArrayList<>();
		if (dir != null) {
			try {
				list.addAll(VaultBackup.snapshots(dir));
			} catch (IOException e) {
				status = Component.translatable("screen.memorypalace.backups_unreadable");
			}
		}
		snapshots = list.reversed();
		int pages = Math.max(1, (snapshots.size() + perPage() - 1) / perPage());
		page = Math.clamp(page, 0, pages - 1);
	}

	private static String shown(VaultBackup.Snapshot snapshot) {
		return SHOWN.format(snapshot.time().atZone(ZoneId.systemDefault()));
	}

	private void confirm(VaultBackup.Snapshot snapshot) {
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			this.minecraft.gui.setScreen(this);
			if (yes) {
				restore(snapshot);
			}
		}, Component.translatable("screen.memorypalace.backup_confirm_title"),
				Component.translatable("screen.memorypalace.backup_confirm_message", shown(snapshot)),
				Component.translatable("screen.memorypalace.backup_confirm_yes"), CommonComponents.GUI_CANCEL));
	}

	private void restore(VaultBackup.Snapshot snapshot) {
		busy = true;
		status = Component.translatable("screen.memorypalace.backup_restoring");
		rebuildWidgets();
		PalaceServer.restoreBackup(snapshot.id(), result -> this.minecraft.execute(() -> {
			busy = false;
			// 钥匙圈可能换成了备份时的版本：先上锁，下次用到时按恢复后的钥匙圈重新解锁
			ClientKeys.lock(false);
			if (this.minecraft.player != null) {
				this.minecraft.player.sendSystemMessage(Component.translatable("message.memorypalace.backup_restored",
						shown(snapshot), result.written(), result.movedToTrash(),
						PalaceServer.vault() == null ? result.trash().toString()
								: PalaceServer.vault().root().relativize(result.trash()).toString().replace('\\', '/')));
			}
			status = Component.translatable("screen.memorypalace.backup_restored_short");
			this.minecraft.gui.setScreen(this);
			rebuildWidgets();
		}), error -> this.minecraft.execute(() -> {
			busy = false;
			status = Component.translatable("message.memorypalace.backup_restore_failed");
			rebuildWidgets();
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int x = this.width / 2 - WIDTH / 2;
		int y = TOP;
		int perPage = perPage();
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		if (snapshots.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.backups_empty"), this.width / 2, y + 6, 0xFFC0C0C0);
		}
		int from = page * perPage;
		for (int i = from; i < Math.min(snapshots.size(), from + perPage); i++) {
			Component label = Component.literal(shown(snapshots.get(i)));
			if (i == 0) {
				label = label.copy().append(Component.translatable("screen.memorypalace.backup_latest"));
			}
			graphics.text(this.font, label, x + 4, y + (i - from) * ROW + 6, 0xFFE0E0E0, false);
		}
		int bottom = buttonsY();
		int pages = Math.max(1, (snapshots.size() + perPage - 1) / perPage);
		if (pages > 1) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.backup_page", page + 1, pages),
					this.width / 2, bottom - 26, 0xFFA0A0A0);
		}
		graphics.centeredText(this.font, status, this.width / 2, bottom - 14, 0xFFFFE080);
	}

	@Override
	public void onClose() {
		if (!busy) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}
