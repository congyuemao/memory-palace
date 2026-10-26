package com.memorypalace.client;

import com.memorypalace.client.book.BookSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 记忆宫殿设置。从 Mod Menu（模组列表里的设置按钮）或"打开设置"快捷键进入。
 */
public class SettingsScreen extends Screen {
	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.translatable("settings.memorypalace.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 4 + 24;
		addRenderableWidget(Button.builder(pageSizeLabel(), b -> {
			BookSettings.setPageSize(BookSettings.next(BookSettings.pageSize()));
			b.setMessage(pageSizeLabel());
		}).bounds(x, y, 200, 20).build());
		addRenderableWidget(Button.builder(fontSizeLabel(), b -> {
			BookSettings.setFontSize(BookSettings.next(BookSettings.fontSize()));
			b.setMessage(fontSizeLabel());
		}).bounds(x, y + 24, 200, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(x, y + 72, 200, 20).build());
	}

	private static Component pageSizeLabel() {
		return Component.translatable("settings.memorypalace.pagesize", BookSettings.label(BookSettings.pageSize()));
	}

	private static Component fontSizeLabel() {
		return Component.translatable("settings.memorypalace.fontsize", BookSettings.label(BookSettings.fontSize()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.translatable("settings.memorypalace.hint"),
				this.width / 2, this.height / 4 + 76, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}
