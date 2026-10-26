package com.memorypalace.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 笔记窗口。M0 先是一个只显示标题的空白界面，M1 起在这里显示笔记内容。
 */
public class NoteScreen extends Screen {
	public NoteScreen() {
		super(Component.translatable("screen.memorypalace.note"));
	}

	// 26.x 起界面绘制方法叫 extractRenderState（老教程里叫 render）
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// 颜色是 ARGB：0xFF 开头 = 不透明
		graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
	}

	// 单人游戏里打开笔记时不暂停游戏
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
