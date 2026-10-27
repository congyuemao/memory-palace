package com.memorypalace.client;

import com.memorypalace.client.crypto.InboxCapture;
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
 */
public class QuickCaptureScreen extends Screen {
	private EditBox input;
	private String text;

	public QuickCaptureScreen() {
		this("");
	}

	/** 带着已经写好的内容打开（比如公钥核对时选了"取消保存"，回到输入框继续）。 */
	public QuickCaptureScreen(String text) {
		super(Component.translatable("screen.memorypalace.capture"));
		this.text = text;
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
			onClose();
			if (!captured.isBlank()) {
				// 开着收件箱加密时先在本机加密（公钥对不上时会弹确认框），见 InboxCapture
				InboxCapture.send(captured);
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 26, 0xFFFFFFFF);
		// 告诉玩家会记到哪里（第一次记的时候才会创建这个文件）：房主记在根目录，其他玩家记在自己的个人区
		boolean host = this.minecraft.hasSingleplayerServer() || this.minecraft.player == null;
		String target = Inbox.folderFor(host ? null : this.minecraft.player.getUUID()) + "/" + LocalDate.now() + ".md";
		graphics.centeredText(this.font, Component.translatable("screen.memorypalace.capture_target", target),
				this.width / 2, this.height / 2 + 16, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
