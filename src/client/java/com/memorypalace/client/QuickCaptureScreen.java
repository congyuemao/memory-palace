package com.memorypalace.client;

import com.memorypalace.client.crypto.InboxCapture;
import com.memorypalace.core.vault.CaptureOutbox;
import com.memorypalace.core.vault.Inbox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;

/**
 * 快速收集：一个小输入框，写一句话、回车，就记进今天的收件箱，然后马上回到游戏。
 * 想到什么先记下来，不用想好放哪——有空再整理。
 * 上一条没保存成功（见 InboxCapture）时，按 K 打开的是找回的那一条：回车重试，清空后回车放弃，Esc 稍后再说。
 */
public class QuickCaptureScreen extends Screen {
	private final CaptureOutbox.Draft restored;
	private EditBox input;
	private String text;
	private boolean sent;

	public QuickCaptureScreen() {
		this(null);
	}

	/** 找回一条没保存成功的随手记（null = 空白的输入框）。 */
	public QuickCaptureScreen(CaptureOutbox.Draft restored) {
		super(Component.translatable("screen.memorypalace.capture"));
		this.restored = restored;
		this.text = restored == null ? "" : restored.restoreText();
	}

	@Override
	protected void init() {
		int w = Math.min(320, this.width - 40);
		input = new EditBox(this.font, (this.width - w) / 2, this.height / 2 - 10, w, 20, this.title);
		input.setMaxLength(1000);
		input.setHint(Component.translatable("screen.memorypalace.capture_hint"));
		input.setValue(text);
		input.setResponder(value -> text = value);
		addRenderableWidget(input);
		setInitialFocus(input);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			String captured = text;
			sent = true;
			onClose();
			if (!captured.isBlank()) {
				// 开着收件箱加密时先在本机加密（公钥对不上时会弹确认框）；收到保存结果之前这条都留着，见 InboxCapture
				InboxCapture.send(restored, captured);
			} else if (restored != null) {
				InboxCapture.discard(restored);
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		if (!sent && restored != null) {
			InboxCapture.keepEdit(restored, text); // Esc：这条还留着，下次按 K 接着改
		}
		super.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 26, 0xFFFFFFFF);
		if (restored != null) {
			graphics.centeredText(this.font, Component.translatable("screen.memorypalace.capture_restored"),
					this.width / 2, this.height / 2 - 40, 0xFFFFD060);
		}
		// 告诉玩家会记到哪里（第一次记的时候才会创建这个文件）：房主记在根目录，其他玩家记在自己的个人区
		boolean host = this.minecraft.hasSingleplayerServer() || this.minecraft.player == null;
		LocalDate day = restored == null ? LocalDate.now() : restored.time().toLocalDate();
		String target = Inbox.folderFor(host ? null : this.minecraft.player.getUUID()) + "/" + day + ".md";
		graphics.centeredText(this.font, Component.translatable("screen.memorypalace.capture_target", target),
				this.width / 2, this.height / 2 + 16, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
