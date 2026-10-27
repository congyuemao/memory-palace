package com.memorypalace.client;

import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.crypto.BoxPins;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.PasswordScreen;
import com.memorypalace.client.crypto.RecoveryKeyScreen;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 记忆宫殿设置。从 Mod Menu（模组列表里的设置按钮）或"打开设置"快捷键进入。
 * 密码相关的按钮只有在世界里才能用，因为钥匙圈存在当前世界（服务器）的笔记库里，每人一个。
 */
public class SettingsScreen extends Screen {
	private static final int COLUMN = 150;
	private static final int GAP = 10;
	private static final int ROW = 24;
	private static final int ROWS = 5;
	// 标题 + 栏目名 + 5 行按钮 + "完成" + 提示信息 的总高度
	private static final int CONTENT_HEIGHT = 16 + 14 + ROWS * ROW + 20 + 20 + 16;

	private final Screen parent;
	private Component message = Component.empty();

	/** 第一行按钮的 y：整块内容垂直居中，但至少离顶部留出标题的位置。 */
	private int buttonsTop() {
		return Math.max(32, (this.height - CONTENT_HEIGHT) / 2 + 30);
	}

	public SettingsScreen(Screen parent) {
		super(Component.translatable("settings.memorypalace.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// 两栏：左边"阅读"，右边"加密笔记密码"，整体按内容高度垂直居中（小窗口、大界面缩放也放得下）
		int left = this.width / 2 - COLUMN - GAP / 2;
		int right = this.width / 2 + GAP / 2;
		int y = buttonsTop();
		addRenderableWidget(Button.builder(pageSizeLabel(), b -> {
			BookSettings.setPageSize(BookSettings.next(BookSettings.pageSize()));
			b.setMessage(pageSizeLabel());
		}).bounds(left, y, COLUMN, 20).build());
		addRenderableWidget(Button.builder(fontSizeLabel(), b -> {
			BookSettings.setFontSize(BookSettings.next(BookSettings.fontSize()));
			b.setMessage(fontSizeLabel());
		}).bounds(left, y + ROW, COLUMN, 20).build());
		addRenderableWidget(Button.builder(autoLockLabel(), b -> {
			BookSettings.setAutoLock(BookSettings.next(BookSettings.autoLock()));
			b.setMessage(autoLockLabel());
		}).bounds(left, y + 2 * ROW, COLUMN, 20).build());
		addRenderableWidget(Button.builder(inboxEncryptionLabel(), b -> {
			BookSettings.setInboxEncryption(!BookSettings.inboxEncryption());
			b.setMessage(inboxEncryptionLabel());
		}).bounds(left, y + 3 * ROW, COLUMN, 20).build());

		// 密码相关：都要先输入当前密码证明是本人。钥匙圈存在当前世界（服务器）上，每人一个，所以要在世界里才能用
		boolean inWorld = this.minecraft.player != null;
		Button change = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.change_password"),
				b -> withKeyring(keyring -> this.minecraft.gui.setScreen(PasswordScreen.verify(keyring,
						Component.translatable("crypto.memorypalace.verify_change"), this,
						(password, key) -> this.minecraft.gui.setScreen(
								PasswordScreen.newPassword(keyring, key, this, () -> this.minecraft.gui.setScreen(this)))))))
				.bounds(right, y, COLUMN, 20).build());
		Button regen = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.new_recovery"),
				b -> withKeyring(keyring -> this.minecraft.gui.setScreen(PasswordScreen.verify(keyring,
						Component.translatable("crypto.memorypalace.verify_recovery"), this, (password, key) -> {
							try {
								// 密码只在这个回调里有效（回调结束就清零），所以"带新恢复密钥"和"不带恢复密钥"两个版本现在就算好
								KeyRing.Created created = KeyRing.regenerateRecovery(keyring, password);
								created.key().destroy(); // 多解出来的一份主密钥，用不着
								String without = KeyRing.removeRecovery(keyring, password);
								ClientKeys.writeKeyring(created.content(), keyring, ok -> {
									if (!ok) {
										this.minecraft.gui.setScreen(this);
										return;
									}
									this.minecraft.gui.setScreen(new RecoveryKeyScreen(created.recoveryKey(),
											() -> this.minecraft.gui.setScreen(this),
											// 选择"不使用恢复密钥"：写入不带恢复密钥的版本
											() -> writeAndShow(without, created.content(), "settings.memorypalace.recovery_removed")));
								});
							} catch (Exception e) {
								this.minecraft.gui.setScreen(this);
								show("crypto.memorypalace.error");
							}
						}))))
				.bounds(right, y + ROW, COLUMN, 20).build());
		Button remove = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.remove_recovery"),
				b -> withKeyring(keyring -> this.minecraft.gui.setScreen(PasswordScreen.verify(keyring,
						Component.translatable("crypto.memorypalace.verify_remove_recovery"), this, (password, key) -> {
							try {
								writeAndShow(KeyRing.removeRecovery(keyring, password), keyring, "settings.memorypalace.recovery_removed");
							} catch (Exception e) {
								this.minecraft.gui.setScreen(this);
								show("crypto.memorypalace.error");
							}
						}))))
				.bounds(right, y + 2 * ROW, COLUMN, 20).build());
		// 忘记密码：用恢复密钥重置（原密码没有保存，无法找回）
		Button forgot = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.forgot_password"),
				b -> withKeyring(keyring -> {
					if (!hasRecovery(keyring)) {
						show("settings.memorypalace.no_recovery");
						return;
					}
					this.minecraft.gui.setScreen(PasswordScreen.recover(keyring, this, () -> this.minecraft.gui.setScreen(this)));
				}))
				.bounds(right, y + 3 * ROW, COLUMN, 20).build());
		// 信任新的收件箱公钥：并排显示记住的旧指纹和服务器上的新指纹，确认后才替换（只在别人的服务器上有用）
		Button trust = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.trust_box"),
				b -> withKeyring(this::confirmTrust))
				.bounds(right, y + 4 * ROW, COLUMN, 20).build());
		change.active = regen.active = remove.active = forgot.active = inWorld;
		trust.active = inWorld && !this.minecraft.hasSingleplayerServer();

		// 最下面一行：左边"修改按键"（打开原版按键设置页），右边"完成"
		int bottom = y + ROWS * ROW + 20;
		addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.edit_keys"),
				b -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options)))
				.bounds(left, bottom, COLUMN, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(right, bottom, COLUMN, 20).build());
	}

	/** 按键一览：每个按键"名称 键位"，和别的按键冲突的标红。 */
	private Component keySummary() {
		var line = Component.translatable("settings.memorypalace.keys").copy();
		boolean first = true;
		for (KeyMapping key : PalaceKeys.all()) {
			if (key.isUnbound()) {
				continue;
			}
			if (!first) {
				line.append("  ·  ");
			}
			first = false;
			boolean conflict = !PalaceKeys.conflictsOf(key).isEmpty();
			line.append(Component.translatable(key.getName()).copy().append(" ").append(key.getTranslatedKeyMessage())
					.withColor(conflict ? 0xFF5555 : 0xC0C0C0));
		}
		return line;
	}

	private void confirmTrust(String keyring) {
		byte[] publicKey;
		try {
			publicKey = KeyRing.boxPublic(keyring);
		} catch (IllegalArgumentException e) {
			publicKey = null;
		}
		if (publicKey == null) {
			show("settings.memorypalace.trust_box_none");
			return;
		}
		String fresh = InboxCipher.fingerprint(publicKey);
		String pinned = BoxPins.pinned();
		if (fresh.equals(pinned)) {
			show("settings.memorypalace.trust_box_same");
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				BoxPins.trust(fresh);
			}
			this.minecraft.gui.setScreen(this);
			show(yes ? "settings.memorypalace.trust_box_done" : "settings.memorypalace.trust_box_kept");
		}, Component.translatable("settings.memorypalace.trust_box_title"),
				Component.translatable("settings.memorypalace.trust_box_message",
						pinned == null ? Component.translatable("settings.memorypalace.trust_box_unknown") : Component.literal(pinned),
						fresh)));
	}

	private static boolean hasRecovery(String keyring) {
		try {
			return KeyRing.hasRecovery(keyring);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** 拿到钥匙圈再继续；还没启用过加密就提示一下。 */
	private void withKeyring(java.util.function.Consumer<String> then) {
		ClientKeys.withKeyring(keyring -> {
			if (keyring.isEmpty()) {
				show("message.memorypalace.no_keyring");
			} else {
				then.accept(keyring);
			}
		});
	}

	/** 写钥匙圈，等服务端确认存盘后回到设置页并显示结果（失败时 ClientKeys 会在聊天栏说明原因）。 */
	private void writeAndShow(String newContent, String oldContent, String successKey) {
		ClientKeys.writeKeyring(newContent, oldContent, ok -> {
			this.minecraft.gui.setScreen(this);
			show(ok ? successKey : "crypto.memorypalace.error");
		});
	}

	private void show(String key) {
		message = Component.translatable(key);
	}

	private static Component pageSizeLabel() {
		return Component.translatable("settings.memorypalace.pagesize", BookSettings.label(BookSettings.pageSize()));
	}

	private static Component fontSizeLabel() {
		return Component.translatable("settings.memorypalace.fontsize", BookSettings.label(BookSettings.fontSize()));
	}

	private static Component autoLockLabel() {
		return Component.translatable("settings.memorypalace.autolock", BookSettings.label(BookSettings.autoLock()));
	}

	private static Component inboxEncryptionLabel() {
		return Component.translatable("settings.memorypalace.inbox_encryption", Component.translatable(
				BookSettings.inboxEncryption() ? "settings.memorypalace.on" : "settings.memorypalace.off"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int y = buttonsTop();
		graphics.centeredText(this.font, this.title, this.width / 2, y - 30, 0xFFFFFFFF);
		// 两栏各自的栏目名
		graphics.centeredText(this.font, Component.translatable("settings.memorypalace.reading_section"),
				this.width / 2 - GAP / 2 - COLUMN / 2, y - 12, 0xFFA0A0A0);
		graphics.centeredText(this.font, Component.translatable("settings.memorypalace.password_section"),
				this.width / 2 + GAP / 2 + COLUMN / 2, y - 12, 0xFFA0A0A0);
		// 按键一览（冲突的标红），在两栏按钮下面、"修改按键/完成"上面
		graphics.centeredText(this.font, keySummary(), this.width / 2, y + ROWS * ROW + 6, 0xFFA0A0A0);
		graphics.centeredText(this.font, message, this.width / 2, y + ROWS * ROW + 46, 0xFFFFE080);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}
