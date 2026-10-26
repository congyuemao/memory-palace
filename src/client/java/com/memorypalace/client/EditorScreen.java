package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.OpenNotePayload;
import com.memorypalace.mc.PalaceServer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 游戏内编辑器：还是那本摊开的书，左页直接在纸上写 Markdown，右页实时预览。
 * Ctrl+S 保存；有没保存的修改时按 Esc 会先确认。保存时如果文件在外部被改过，会提示冲突而不是悄悄覆盖。
 */
public class EditorScreen extends BookScreen {
	// 一次排版超过这么多毫秒就算"慢"，改成停手后再刷新
	private static final long SLOW_LAYOUT_MS = 25;
	private static int sessionCounter;

	private String text;          // 编辑框里的当前内容（重新 init 时用它恢复）
	private String savedBody;     // 最后一次保存成功的内容
	private String baseHash;      // savedBody 的指纹，保存时用来检查外部有没有改过

	private MultiLineEditBox editBox;
	private List<List<BookLine>> preview = List.of();
	private int previewPage;
	private boolean previewDirty = true;
	private long lastEdit;
	private long lastLayoutMs;

	private int pendingSession = -1;
	private String pendingBody;
	private boolean closeAfterSave;
	private Component status = Component.empty();

	public EditorScreen(UUID noteId, Component title, String body) {
		super(noteId, title);
		this.text = body;
		this.savedBody = body;
		this.baseHash = Vault.hash(body);
	}

	private boolean dirty() {
		return !text.equals(savedBody);
	}

	@Override
	protected void init() {
		super.init();
		int buttonH = 18;
		int boxHeight = textHeight() - buttonH - 4;
		editBox = MultiLineEditBox.builder()
				.setX(leftTextX()).setY(top + TEXT_TOP)
				.setShowBackground(false)
				.setTextColor(BookLayout.TEXT).setTextShadow(false).setCursorColor(BookLayout.TEXT)
				.setPlaceholder(Component.translatable("screen.memorypalace.editor_placeholder"))
				.build(this.font, textWidth(), boxHeight, this.title);
		editBox.setCharacterLimit(OpenNotePayload.MAX_BODY);
		editBox.setValue(text);
		editBox.setValueListener(value -> {
			text = value;
			previewDirty = true;
			lastEdit = System.currentTimeMillis();
		});
		addRenderableWidget(editBox);
		setInitialFocus(editBox);

		int buttonY = top + TEXT_TOP + textHeight() - buttonH;
		addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.save"), b -> save(false))
				.bounds(leftTextX(), buttonY, 56, buttonH).build());
		// 单人游戏才能直接打开本机文件
		if (this.minecraft.hasSingleplayerServer()) {
			addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.open_external"), b -> openExternal())
					.bounds(leftTextX() + 60, buttonY, Math.min(110, textWidth() - 60), buttonH).build());
		}
		relayout();
	}

	@Override
	protected void relayout() {
		if (this.font == null) {
			return;
		}
		preview = layout(text);
		previewPage = Math.clamp(previewPage, 0, preview.size() - 1);
		previewDirty = false;
	}

	/** 内容变了就在下一帧刷新预览；只有排版很慢（超长笔记）时才等停手再刷，免得打字卡。 */
	private void refreshPreviewIfNeeded() {
		if (!previewDirty) {
			return;
		}
		long delay = lastLayoutMs > SLOW_LAYOUT_MS ? lastLayoutMs * 2 : 0;
		if (System.currentTimeMillis() - lastEdit < delay) {
			return;
		}
		long start = System.nanoTime();
		relayout();
		lastLayoutMs = (System.nanoTime() - start) / 1_000_000;
	}

	// ---- 保存 ----

	private void save(boolean force) {
		String body = text;
		int session = ++sessionCounter;
		pendingSession = session;
		pendingBody = body;
		List<String> parts = chunks(body);
		for (int i = 0; i < parts.size(); i++) {
			ClientPlayNetworking.send(new EditPayloads.SaveChunk(noteId, session, i, parts.size(),
					force ? "" : baseHash, parts.get(i)));
		}
		status = Component.translatable("screen.memorypalace.saving");
	}

	/** 按 CHUNK_CHARS 切块，不把 emoji 之类的"代理对"切成两半。空正文也要发一块。 */
	private static List<String> chunks(String body) {
		List<String> out = new java.util.ArrayList<>();
		int i = 0;
		do {
			int end = Math.min(body.length(), i + EditPayloads.CHUNK_CHARS);
			if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) {
				end--;
			}
			out.add(body.substring(i, end));
			i = end;
		} while (i < body.length());
		return out;
	}

	/** 服务端回复了保存结果（在 MemoryPalaceClient 里转过来）。 */
	public void onSaveResult(EditPayloads.SaveResult result) {
		if (result.session() != pendingSession) {
			return; // 旧的保存，忽略
		}
		pendingSession = -1;
		switch (result.result()) {
			case "OK" -> {
				savedBody = pendingBody;
				baseHash = result.newHash();
				status = Component.translatable("screen.memorypalace.saved");
				if (closeAfterSave) {
					backToReading();
				}
			}
			case "CONFLICT" -> this.minecraft.gui.setScreen(new ConfirmScreen(overwrite -> {
						this.minecraft.gui.setScreen(this);
						if (overwrite) {
							save(true);
						} else {
							status = Component.translatable("screen.memorypalace.conflict_kept");
						}
					},
					Component.translatable("screen.memorypalace.conflict_title"),
					Component.translatable("screen.memorypalace.conflict_message"),
					Component.translatable("screen.memorypalace.conflict_overwrite"),
					Component.translatable("screen.memorypalace.conflict_cancel")));
			case "MISSING" -> status = Component.translatable("screen.memorypalace.save_missing");
			default -> status = Component.translatable("screen.memorypalace.save_failed");
		}
		closeAfterSave = false;
	}

	private void openExternal() {
		Vault vault = PalaceServer.vault();
		Optional<Path> path = vault == null ? Optional.empty() : vault.pathOf(noteId);
		path.ifPresent(ExternalEditor::open);
	}

	// ---- 关闭 ----

	@Override
	public void onClose() {
		if (!dirty()) {
			backToReading();
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(discard -> {
					if (discard) {
						backToReading();
					} else {
						this.minecraft.gui.setScreen(this);
					}
				},
				Component.translatable("screen.memorypalace.unsaved_title"),
				Component.translatable("screen.memorypalace.unsaved_message"),
				Component.translatable("screen.memorypalace.unsaved_discard"),
				Component.translatable("screen.memorypalace.unsaved_keep")));
	}

	private void backToReading() {
		this.minecraft.gui.setScreen(new NoteScreen(noteId, this.title, savedBody));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Ctrl+S（Mac 上是 Cmd+S）保存
		if (event.key() == InputConstants.KEY_S && event.hasControlDownWithQuirk()) {
			save(false);
			return true;
		}
		return super.keyPressed(event);
	}

	// ---- 绘制 ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		refreshPreviewIfNeeded();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (previewPage < preview.size()) {
			drawPage(graphics, preview.get(previewPage), rightTextX());
		}
		int indicatorY = top + INDICATOR_Y;
		// 左页顶部：编辑状态；右页顶部：预览页码
		Component left = dirty() && status.getString().isEmpty()
				? Component.translatable("screen.memorypalace.unsaved")
				: status;
		graphics.text(this.font, Component.translatable("screen.memorypalace.editing").append(left),
				leftTextX(), indicatorY, BookLayout.MUTED, false);
		String n = Component.translatable("screen.memorypalace.preview").getString() + " " + (previewPage + 1) + " / " + preview.size();
		graphics.text(this.font, n, rightTextX() + textWidth() - this.font.width(n), indicatorY, BookLayout.MUTED, false);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// 右半边滚轮翻预览页，左半边交给编辑框自己滚动
		if (mouseX >= spineX() && scrollY != 0) {
			previewPage = Math.clamp(previewPage + (scrollY < 0 ? 1 : -1), 0, preview.size() - 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
