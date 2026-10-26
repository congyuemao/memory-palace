package com.memorypalace.client.crypto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 显示一次恢复密钥。三个选择：复制、"我已经抄好了"、"不要恢复密钥"。按 Esc 不会关掉（免得手滑错过）。
 */
public class RecoveryKeyScreen extends Screen {
	private final String recoveryKey;
	private final Runnable onKept;
	private final Runnable onSkip;
	private Component copied = Component.empty();

	public RecoveryKeyScreen(String recoveryKey, Runnable onKept, Runnable onSkip) {
		super(PasswordScreen.t("recovery_title"));
		this.recoveryKey = recoveryKey;
		this.onKept = onKept;
		this.onSkip = onSkip;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 + 30;
		addRenderableWidget(Button.builder(PasswordScreen.t("recovery_copy"), b -> {
			Minecraft.getInstance().keyboardHandler.setClipboard(recoveryKey);
			copied = PasswordScreen.t("recovery_copied");
		}).bounds(x, y, 200, 20).build());
		addRenderableWidget(Button.builder(PasswordScreen.t("recovery_kept"), b -> onKept.run())
				.bounds(x, y + 24, 200, 20).build());
		addRenderableWidget(Button.builder(PasswordScreen.t("recovery_skip"), b ->
				Minecraft.getInstance().gui.setScreen(new ConfirmScreen(sure -> {
							if (sure) {
								onSkip.run();
							} else {
								Minecraft.getInstance().gui.setScreen(this);
							}
						},
						PasswordScreen.t("recovery_skip_title"),
						PasswordScreen.t("recovery_skip_message"))))
				.bounds(x, y + 48, 200, 20).build());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int cx = this.width / 2;
		int y = this.height / 2 - 80;
		graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
		y += 16;
		for (var line : this.font.split(PasswordScreen.t("recovery_message"), Math.min(this.width - 40, 420))) {
			graphics.centeredText(this.font, line, cx, y, 0xFFC0C0C0);
			y += 10;
		}
		// 恢复密钥放大画，方便抄；屏幕放不下两倍时自动缩小
		float scale = Math.min(2.0F, (this.width - 24) / (float) this.font.width(recoveryKey));
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, this.height / 2f + 6);
		graphics.pose().scale(scale, scale);
		graphics.centeredText(this.font, recoveryKey, 0, -4, 0xFFFFE080);
		graphics.pose().popMatrix();
		graphics.centeredText(this.font, copied, cx, this.height / 2 + 102, 0xFF80FF80);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
