package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.OpenNotePayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 阅读笔记：摊开的原版书（左右两页），翻页按钮 / 滚轮翻页。书脊下方的 ✎ 进入游戏内编辑器。
 * 书页大小、字体大小在设置里调（SettingsScreen）。
 */
public class NoteScreen extends BookScreen {
	private String body;          // 明文（加密笔记已经解密）
	private String storedHash;    // 文件里存的那份正文（加密笔记是密文）的指纹，编辑器保存时检查冲突用
	private List<List<BookLine>> pages = List.of();
	private int spread; // 第几个"对开页"，0 = 第 1、2 页

	private PageButton backButton;
	private PageButton forwardButton;

	/** 按 M 打开的窗口：还没有选笔记时显示说明。 */
	public NoteScreen() {
		this(null, Component.translatable("screen.memorypalace.note"),
				Component.translatable("screen.memorypalace.empty_hint").getString());
	}

	public NoteScreen(UUID noteId, Component title, String body) {
		this(noteId, title, body, false, Vault.hash(body));
	}

	public NoteScreen(UUID noteId, Component title, String body, boolean encrypted, String storedHash) {
		super(noteId, title, encrypted);
		this.body = body;
		this.storedHash = storedHash;
	}

	/** 文件在外部被改了：换上新内容，尽量停在原来那一页。 */
	public void updateBody(String newBody, String newStoredHash) {
		this.body = newBody;
		this.storedHash = newStoredHash;
		// 外部可能换了图片：重新请求这篇笔记的图
		NoteImageStore.forget(noteId);
		relayout();
	}

	@Override
	protected void init() {
		super.init();
		if (closeIfLocked()) {
			return;
		}
		relayout();
		int buttonY = top + pageH - 25;
		// 原版翻页按钮（自带翻书声），放在左页左下角和右页右下角
		backButton = addRenderableWidget(new PageButton(leftTextX(), buttonY, false, b -> flip(-1), true));
		forwardButton = addRenderableWidget(new PageButton(rightTextX() + textWidth() - 23, buttonY, true, b -> flip(1), true));

		// ✎ 进入游戏内编辑器；做成书脊下方的小按钮，不占阅读空间
		if (noteId != null) {
			addRenderableWidget(Button.builder(Component.literal("✎"), b -> openEditor())
					.bounds(spineX() - 10, Math.min(this.height - 21, top + pageH + 1), 20, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.edit"))).build());
		}
		updateButtons();
	}

	@Override
	protected void relayout() {
		if (this.font == null) {
			return; // 还没 init，init 时会再排一次
		}
		pages = layout(body);
		spread = Math.clamp(spread, 0, maxSpread());
		updateButtons();
	}

	private void openEditor() {
		// 游戏内输入框处理不了太长的文本，超过上限会被截断——截断后一保存就丢了后半段，所以干脆不打开
		if (body.length() > OpenNotePayload.MAX_BODY) {
			this.minecraft.player.sendSystemMessage(Component.translatable("message.memorypalace.too_long_to_edit", OpenNotePayload.MAX_BODY));
			return;
		}
		this.minecraft.gui.setScreen(new EditorScreen(noteId, this.title, body, encrypted, storedHash));
	}

	private int maxSpread() {
		return Math.max(0, (pages.size() - 1) / 2);
	}

	private void flip(int delta) {
		spread = Math.clamp(spread + delta, 0, maxSpread());
		updateButtons();
	}

	private void updateButtons() {
		if (backButton != null) {
			backButton.visible = spread > 0;
			forwardButton.visible = spread < maxSpread();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (encrypted && !ClientKeys.unlocked()) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		int leftIndex = spread * 2;
		if (leftIndex < pages.size()) {
			drawPage(graphics, pages.get(leftIndex), leftTextX());
		}
		if (leftIndex + 1 < pages.size()) {
			drawPage(graphics, pages.get(leftIndex + 1), rightTextX());
		}

		// 页码，位置和原版的"第 x 页"一样在页面上方（不跟字体一起放大）
		int indicatorY = top + INDICATOR_Y;
		if (leftIndex < pages.size()) {
			graphics.text(this.font, String.valueOf(leftIndex + 1), leftTextX(), indicatorY, BookLayout.MUTED, false);
		}
		if (leftIndex + 1 < pages.size()) {
			String n = (leftIndex + 2) + " / " + pages.size();
			graphics.text(this.font, n, rightTextX() + textWidth() - this.font.width(n), indicatorY, BookLayout.MUTED, false);
		}
		drawLockWarning(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (scrollY != 0) {
			flip(scrollY < 0 ? 1 : -1);
		}
		return true;
	}
}
