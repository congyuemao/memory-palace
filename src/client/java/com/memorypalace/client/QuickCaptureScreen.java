package com.memorypalace.client;

import com.memorypalace.core.vault.Inbox;
import com.memorypalace.mc.ToolPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 快速收集：一个小输入框，写一句话、回车，就记进今天的收件箱，然后马上回到游戏。
 * 想到什么先记下来，不用想好放哪——有空再整理。
 */
public class QuickCaptureScreen extends Screen {
	private EditBox input;
	private String text = "";

	public QuickCaptureScreen() {
		super(Component.translatable("screen.memorypalace.capture"));
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
			if (!text.isBlank()) {
				// 带上本机的本地时间和时区："今天"按玩家所在地算（出国、跨日期变更线都对）
				ClientPlayNetworking.send(new ToolPayloads.Capture(text, OffsetDateTime.now().toString()));
			}
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 26, 0xFFFFFFFF);
		// 告诉玩家会记到哪里（第一次记的时候才会创建这个文件）
		String target = Inbox.FOLDER + "/" + LocalDate.now() + ".md";
		graphics.centeredText(this.font, Component.translatable("screen.memorypalace.capture_target", target),
				this.width / 2, this.height / 2 + 16, 0xFFA0A0A0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
