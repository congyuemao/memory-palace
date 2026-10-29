package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.NoteSaver;
import com.memorypalace.client.crypto.NoteSealers;
import com.memorypalace.core.crypto.WrongKeyException;
import com.memorypalace.core.editor.UndoHistory;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.EditPayloads;
import com.memorypalace.mc.OpenNotePayload;
import com.memorypalace.mc.PalaceServer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 游戏内编辑器：还是那本摊开的书，左页直接在纸上写 Markdown，右页实时预览。
 * Ctrl+S 保存，Ctrl+Z 撤销，Ctrl+Y / Ctrl+Shift+Z 重做；有没保存的修改时按 Esc 会先确认。保存时如果文件在外部被改过，会提示冲突而不是悄悄覆盖。
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
	// status 是针对哪份正文说的：正文还是这样就照常显示（保存失败、冲突的提示不被"未保存"盖住），改了字就显示"未保存"
	private String statusFor;
	// 自动保存（见 BookSettings.AutoSave）：从上次保存（或打开编辑器）算起，到时间且有没保存的修改就存一次
	private long lastSaveAt = System.currentTimeMillis();
	private boolean autoSaving;

	// 撤销 / 重做历史；重新 init（改窗口大小）时不丢
	private final UndoHistory history;
	private boolean applyingHistory;
	// 文件夹里的笔记（MPAL2，用文件夹钥匙加密）：上锁后不能在这里补输保险库密码来保存
	private final boolean folderNote;

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
		this.history = new UndoHistory(this.text);
		this.folderNote = encrypted && NoteSealers.isEnvelope(noteId);
	}

	/** 上次上锁前有没保存成功的草稿：解密后放回编辑框（标记为未保存）。 */
	private void restoreDraft() {
		String draft = ClientKeys.takeDraft(noteId);
		if (draft == null || !NoteSealers.available(noteId)) {
			return;
		}
		try {
			this.text = NoteSealers.open(noteId, draft);
			this.status = Component.translatable("screen.memorypalace.draft_restored");
			this.statusFor = this.text;
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
			if (!applyingHistory) {
				history.changed(value, lastEdit);
			}
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
		autoSaving = false;
		if (encrypted && !NoteSealers.available(noteId)) {
			if (folderNote) {
				// 文件夹笔记的内容钥匙上锁时已经清零：没法在这里补输文件夹密码，草稿已暂存，重新打开这篇即可恢复
				status = Component.translatable("screen.memorypalace.save_locked");
				return;
			}
			// 编辑时自动上锁了：先解锁再保存
			ClientKeys.requireUnlocked(this, () -> {
				this.minecraft.gui.setScreen(this);
				save(force);
			});
			return;
		}
		String body = text;
		String stored = encrypted ? NoteSealers.seal(noteId, body) : body;
		send(body, stored, force);
	}

	private void send(String body, String stored, boolean force) {
		saving = true;
		lastSaveAt = System.currentTimeMillis();
		status = Component.translatable("screen.memorypalace.saving");
		NoteSaver.save(noteId, stored, force ? null : baseHash, result -> {
			if ("OK".equals(result.result()) && encrypted) {
				NoteSealers.saved(noteId, stored);
			}
			onSaveResult(result, body);
		});
	}

	/**
	 * 每刻检查要不要自动保存：设置里开着、有没保存的修改、离上次保存够久了、手停了 2 秒（不在打字中途存）。
	 * 加密笔记的钥匙已经清零（自动上锁了）就不存，也不弹解锁框——草稿上锁时已经暂存了。
	 * 自动保存不覆盖外部的修改：冲突时只提示，不弹框打断。
	 */
	@Override
	public void tick() {
		super.tick();
		int minutes = BookSettings.autoSave().minutes;
		long now = System.currentTimeMillis();
		if (minutes <= 0 || saving || !dirty() || now - lastSaveAt < minutes * 60_000L || now - lastEdit < 2_000
				|| encrypted && !NoteSealers.available(noteId)) {
			return;
		}
		String body = text;
		String stored = encrypted ? NoteSealers.seal(noteId, body) : body;
		autoSaving = true;
		send(body, stored, false);
	}

	/**
	 * 上锁前调用（钥匙马上要清零了）：有没保存的内容就先加密暂存一份草稿，再尝试保存。
	 * 保存成功会删掉草稿；失败的话下次解锁后打开编辑器会恢复它。
	 */
	public void stashDraftAndSave() {
		if (!encrypted || !dirty() || !NoteSealers.available(noteId)) {
			return;
		}
		String body = text;
		String cipher = NoteSealers.seal(noteId, body);
		ClientKeys.stashDraft(noteId, cipher);
		if (!saving) {
			send(body, cipher, false);
		}
	}

	private void onSaveResult(EditPayloads.SaveResult result, String body) {
		saving = false;
		boolean auto = autoSaving;
		autoSaving = false;
		statusFor = text;
		if (auto && "CONFLICT".equals(result.result())) {
			status = Component.translatable("screen.memorypalace.autosave_conflict");
			return;
		}
		switch (result.result()) {
			case "OK" -> {
				savedBody = body;
				baseHash = result.newHash();
				statusFor = body; // 保存途中又打了字：显示"未保存"
				status = Component.translatable(auto ? "screen.memorypalace.autosaved" : "screen.memorypalace.saved");
				// 保存的就是暂存的那份（或更新的）：草稿不需要了
				if (body.equals(text)) {
					ClientKeys.dropDraft(noteId);
				}
			}
			case "REFUSED" -> status = Component.translatable("screen.memorypalace.save_refused");
			case "DENIED" -> status = Component.translatable("screen.memorypalace.save_denied");
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
			case "BUSY" -> status = Component.translatable("screen.memorypalace.save_busy");
			case "TOO_LONG" -> status = Component.translatable("screen.memorypalace.save_too_long");
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
		this.minecraft.gui.setScreen(new NoteScreen(noteId, this.title, savedBody, encrypted, baseHash, true));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// 移动光标、改选区（方向键、Home/End、翻页、Ctrl+A）之后的修改另算一步，不和之前的打字合并
		switch (event.key()) {
			case InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN,
					InputConstants.KEY_HOME, InputConstants.KEY_END, InputConstants.KEY_PAGEUP, InputConstants.KEY_PAGEDOWN ->
					history.breakMerge();
			case InputConstants.KEY_A -> {
				if (event.hasControlDownWithQuirk()) {
					history.breakMerge();
				}
			}
			default -> {
			}
		}
		if (event.hasControlDownWithQuirk()) {
			// Ctrl+S（Mac 上是 Cmd+S）保存；Ctrl+Z 撤销；Ctrl+Y 或 Ctrl+Shift+Z 重做
			switch (event.key()) {
				case InputConstants.KEY_S -> {
					save(false);
					return true;
				}
				case InputConstants.KEY_Z -> {
					apply(event.hasShiftDown() ? history.redo() : history.undo());
					return true;
				}
				case InputConstants.KEY_Y -> {
					apply(history.redo());
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 在编辑框里点一下（挪光标、双击选词、开始拖选）之后的修改另算一步
		if (editBox != null && editBox.isMouseOver(event.x(), event.y())) {
			history.breakMerge();
		}
		return super.mouseClicked(event, doubleClick);
	}

	// ---- 撤销 / 重做 ----

	// 原版多行输入框没有公开光标位置：用反射拿到里面的 MultilineTextField 来挪光标（26.x 起不混淆，字段名就是 textField）。
	// 拿不到就算了，光标留在末尾，撤销本身不受影响
	private static final Field TEXT_FIELD = findTextField();

	private static Field findTextField() {
		try {
			Field field = MultiLineEditBox.class.getDeclaredField("textField");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** 把编辑框恢复成历史里的某个版本；state 为 null（没得撤销/重做）时什么都不做。 */
	private void apply(UndoHistory.State state) {
		if (state == null || editBox == null) {
			return;
		}
		applyingHistory = true;
		try {
			editBox.setValue(state.text(), true);
		} finally {
			applyingHistory = false;
		}
		if (TEXT_FIELD != null) {
			try {
				((MultilineTextField) TEXT_FIELD.get(editBox)).seekCursor(Whence.ABSOLUTE, Math.clamp(state.cursor(), 0, text.length()));
			} catch (ReflectiveOperationException | RuntimeException e) {
				// 挪不了光标就留在末尾
			}
		}
	}

	// ---- 绘制 ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (encrypted && !NoteSealers.available(noteId)) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		refreshPreviewIfNeeded();
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (previewPage < preview.size()) {
			drawPage(graphics, preview.get(previewPage), rightTextX());
		}
		int indicatorY = top + INDICATOR_Y;
		// 左页顶部：编辑状态；右页顶部：预览页码
		Component state = dirty() && !saving && !text.equals(statusFor) ? Component.translatable("screen.memorypalace.unsaved") : status;
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
