package com.memorypalace.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记窗口。M1 显示纯文本（自动换行、滚轮滚动），M2 换成 Markdown 渲染。
 */
public class NoteScreen extends Screen {
	private static final int TOP = 36;
	private static final int BOTTOM_MARGIN = 16;
	private static final int MAX_TEXT_WIDTH = 360;

	private final String body;
	private List<FormattedCharSequence> lines = List.of();
	private int scroll; // 向下滚动了多少行

	/** 按 M 打开的空白窗口。 */
	public NoteScreen() {
		this(Component.translatable("screen.memorypalace.note"), "");
	}

	public NoteScreen(Component title, String body) {
		super(title);
		this.body = body;
	}

	// 窗口打开或改变大小时都会调用：按新宽度重新换行
	@Override
	protected void init() {
		int width = textWidth();
		List<FormattedCharSequence> out = new ArrayList<>();
		for (String paragraph : body.split("\r?\n", -1)) {
			if (paragraph.isEmpty()) {
				out.add(FormattedCharSequence.EMPTY);
			} else {
				out.addAll(this.font.split(FormattedText.of(paragraph), width));
			}
		}
		lines = out;
		scroll = Math.min(scroll, maxScroll());
	}

	// 26.x 起界面绘制方法叫 extractRenderState（老教程里叫 render）
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		// 颜色是 ARGB：0xFF 开头 = 不透明
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

		int left = (this.width - textWidth()) / 2;
		int y = TOP;
		for (int i = scroll; i < lines.size() && i < scroll + visibleLines(); i++) {
			graphics.text(this.font, lines.get(i), left, y, 0xFFE0E0E0);
			y += this.font.lineHeight + 1;
		}
		if (maxScroll() > 0) {
			String hint = (scroll + 1) + "/" + (maxScroll() + 1);
			graphics.text(this.font, hint, this.width - this.font.width(hint) - 6, this.height - 12, 0xFF808080);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, maxScroll());
		return true;
	}

	// 单人游戏里打开笔记时不暂停游戏
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int textWidth() {
		return Math.min(MAX_TEXT_WIDTH, this.width - 40);
	}

	private int visibleLines() {
		return Math.max(1, (this.height - TOP - BOTTOM_MARGIN) / (this.font.lineHeight + 1));
	}

	private int maxScroll() {
		return Math.max(0, lines.size() - visibleLines());
	}
}
