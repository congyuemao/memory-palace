package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.NoteSaver;
import com.memorypalace.core.crypto.NoteCipher;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.OpenNotePayload;
import com.memorypalace.mc.PalaceServer;
import com.mojang.blaze3d.platform.InputConstants;
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
 * 加密笔记：保存前在客户端加密，服务端只收到密文。
 */
public class EditorScreen extends BookScreen {
	// 一次排版超过这么多毫秒就算"慢"，改成停手后再刷新
	private static final long SLOW_LAYOUT_MS = 25;

	private String text;          // 编辑框里的当前内容（重新 init 时用它恢复）
	private String savedBody;     // 最后一次保存成功的内容（明文）
	private String baseHash;      // 文件里存的正文（加密笔记是密文）的指纹，保存时检查外部有没有改过

	private MultiLineEditBox editBox;
	private List<List<BookLine>> preview = List.of();
	private int previewPage;
	private boolean previewDirty = true;
	private long lastEdit;
	private long lastLayoutMs;

	private boolean saving;
	private Component status = Component.empty();

	public EditorScreen(UUID noteId, Component title, String body) {
		this(noteId, title, body, false, Vault.hash(body));
	}

	public EditorScreen(UUID noteId, Component title, String body, boolean encrypted, String storedHash) {
		super(noteId, title, encrypted);
		this.text = body;
		this.savedBody = body;
		this.baseHash = storedHash;
		if (encrypted) {
			restoreDraft();
		}
	}

	/** 上次上锁前有没保存成功的草稿：解密后放回编辑框（标记为未保存）。 */
	private void restoreDraft() {
		String draft = ClientKeys.takeDraft(noteId);
		if (draft == null || !ClientKeys.unlocked()) {
			return;
		}
		try {
			this.text = NoteCipher.decrypt(ClientKeys.key(), noteId, draft);
			this.status = Component.translatable("screen.memorypalace.draft_restored");
		} catch (WrongKeyException e) {
			// 草稿解不开就算了，文件里的内容还在
		}
	}

	private boolean dirty() {
		return !text.equals(savedBody);
	}

	@Override
	protected void init() {
		super.init();
		if (closeIfLocked()) {
			return;
		}
		if (encrypted) {
			ClientKeys.setActiveEditor(this);
		}
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
		// 单人游戏才能直接打开本机文件；加密笔记用外部编辑器只能看到乱码，不显示
		if (!encrypted && this.minecraft.hasSingleplayerServer()) {
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
		if (saving) {
			return;
		}
		if (encrypted && !ClientKeys.unlocked()) {
			// 编辑时自动上锁了：先解锁再保存
			ClientKeys.requireUnlocked(this, () -> {
				this.minecraft.gui.setScreen(this);
				save(force);
			});
			return;
		}
		String body = text;
		String stored = encrypted ? NoteCipher.encrypt(ClientKeys.key(), noteId, body) : body;
		send(body, stored, force);
	}

	private void send(String body, String stored, boolean force) {
		saving = true;
		status = Component.translatable("screen.memorypalace.saving");
		NoteSaver.save(noteId, stored, force ? null : baseHash, result -> onSaveResult(result, body));
	}

	/**
	 * 上锁前调用（钥匙马上要清零了）：有没保存的内容就先加密暂存一份草稿，再尝试保存。
	 * 保存成功会删掉草稿；失败的话下次解锁后打开编辑器会恢复它。
	 */
	public void stashDraftAndSave() {
		if (!encrypted || !dirty() || !ClientKeys.unlocked()) {
			return;
		}
		String body = text;
		String cipher = NoteCipher.encrypt(ClientKeys.key(), noteId, body);
		ClientKeys.stashDraft(noteId, cipher);
		if (!saving) {
			send(body, cipher, false);
		}
	}

	private void onSaveResult(EditPayloads.SaveResult result, String body) {
		saving = false;
		switch (result.result()) {
			case "OK" -> {
				savedBody = body;
				baseHash = result.newHash();
				status = Component.translatable("screen.memorypalace.saved");
				// 保存的就是暂存的那份（或更新的）：草稿不需要了
				if (body.equals(text)) {
					ClientKeys.dropDraft(noteId);
				}
			}
			case "REFUSED" -> status = Component.translatable("screen.memorypalace.save_refused");
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
		ClientKeys.clearActiveEditor(this);
		this.minecraft.gui.setScreen(new NoteScreen(noteId, this.title, savedBody, encrypted, baseHash));
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
		if (encrypted && !ClientKeys.unlocked()) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		refreshPreviewIfNeeded();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (previewPage < preview.size()) {
			drawPage(graphics, preview.get(previewPage), rightTextX());
		}
		int indicatorY = top + INDICATOR_Y;
		// 左页顶部：编辑状态；右页顶部：预览页码
		Component state = dirty() && !saving ? Component.translatable("screen.memorypalace.unsaved") : status;
		Component left = Component.translatable(encrypted ? "screen.memorypalace.editing_secret" : "screen.memorypalace.editing")
				.append(state);
		graphics.text(this.font, left, leftTextX(), indicatorY, BookLayout.MUTED, false);
		String n = Component.translatable("screen.memorypalace.preview").getString() + " " + (previewPage + 1) + " / " + preview.size();
		graphics.text(this.font, n, rightTextX() + textWidth() - this.font.width(n), indicatorY, BookLayout.MUTED, false);
		drawLockWarning(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// 右半边滚轮翻预览页，左半边交给编辑框自己滚动
		if (mouseX >= spineX() && scrollY != 0) {
			if (encrypted) {
				ClientKeys.touch();
			}
			previewPage = Math.clamp(previewPage + (scrollY < 0 ? 1 : -1), 0, preview.size() - 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}
}
