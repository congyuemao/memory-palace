package com.memorypalace.client;

import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.crypto.BoxPins;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.PasswordScreen;
import com.memorypalace.client.crypto.RecoveryKeyScreen;
import com.memorypalace.client.crypto.SecurityKey;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.PalaceServer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆宫殿设置。从 Mod Menu（模组列表里的设置按钮）或"打开设置"快捷键进入。
 * 分三页：阅读与编辑、加密与解锁、笔记库与备份。
 * 密码相关的按钮只有在世界里才能用，因为钥匙圈存在当前世界（服务器）的笔记库里，每人一个；
 * 笔记库与备份只在单人游戏、自己开的局域网里能用（文件在本机硬盘上）。
 */
public class SettingsScreen extends Screen {
	private enum Tab { READING, SECURITY, VAULT }

	private static final int WIDE = 210;   // 单栏按钮宽
	private static final int COLUMN = 150; // 两栏时每栏宽
	private static final int GAP = 10;
	private static final int ROW = 24;
	private static final int TAB_WIDTH = 104;
	// 标题 + 页签 + 4 行按钮 + 说明行 + 提示信息 + "完成" 的总高度
	private static final int CONTENT_HEIGHT = 20 + 20 + 14 + 4 * ROW + 14 + 16 + 20;

	// 记住上次看的是哪一页（这次打开游戏期间）
	private static Tab tab = Tab.READING;

	private final Screen parent;
	private Component message = Component.empty();

	public SettingsScreen(Screen parent) {
		super(Component.translatable("settings.memorypalace.title"));
		this.parent = parent;
	}

	/** 页签那一行的 y：整块内容垂直居中，但至少离顶部留出标题的位置。 */
	private int tabsTop() {
		return Math.max(28, (this.height - CONTENT_HEIGHT) / 2 + 20);
	}

	private int contentTop() {
		return tabsTop() + 20 + 14;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - (3 * TAB_WIDTH + 2 * 4) / 2;
		for (Tab t : Tab.values()) {
			Button button = addRenderableWidget(Button.builder(tabLabel(t), b -> {
				tab = t;
				message = Component.empty();
				rebuildWidgets();
			}).bounds(x, tabsTop(), TAB_WIDTH, 20).build());
			button.active = t != tab; // 当前页的页签按下去
			x += TAB_WIDTH + 4;
		}
		switch (tab) {
			case READING -> initReading();
			case SECURITY -> initSecurity();
			case VAULT -> initVault();
		}
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(this.width / 2 - WIDE / 2, contentTop() + 4 * ROW + 14 + 16, WIDE, 20).build());
	}

	private static Component tabLabel(Tab t) {
		return Component.translatable(switch (t) {
			case READING -> "settings.memorypalace.tab_reading";
			case SECURITY -> "settings.memorypalace.tab_security";
			case VAULT -> "settings.memorypalace.tab_vault";
		});
	}

	// ---- 阅读与编辑 ----

	private void initReading() {
		int x = this.width / 2 - WIDE / 2;
		int y = contentTop();
		addRenderableWidget(Button.builder(pageSizeLabel(), b -> {
			BookSettings.setPageSize(BookSettings.next(BookSettings.pageSize()));
			b.setMessage(pageSizeLabel());
		}).bounds(x, y, WIDE, 20).build());
		addRenderableWidget(Button.builder(fontSizeLabel(), b -> {
			BookSettings.setFontSize(BookSettings.next(BookSettings.fontSize()));
			b.setMessage(fontSizeLabel());
		}).bounds(x, y + ROW, WIDE, 20).build());
		addRenderableWidget(Button.builder(autoSaveLabel(), b -> {
			BookSettings.setAutoSave(BookSettings.next(BookSettings.autoSave()));
			b.setMessage(autoSaveLabel());
		}).bounds(x, y + 2 * ROW, WIDE, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.edit_keys"),
				b -> this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options)))
				.bounds(x, y + 3 * ROW, WIDE, 20).build());
	}

	// ---- 加密与解锁 ----

	private void initSecurity() {
		// 两栏：左边密码和恢复密钥，右边其他解锁方式和收件箱
		int left = this.width / 2 - COLUMN - GAP / 2;
		int right = this.width / 2 + GAP / 2;
		int y = contentTop();
		// 密码相关：都要先输入当前密码证明是本人。钥匙圈存在当前世界（服务器）上，每人一个，所以要在世界里才能用
		boolean inWorld = this.minecraft.player != null;
		Button change = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.change_password"),
				b -> withKeyring(keyring -> this.minecraft.gui.setScreen(PasswordScreen.verify(keyring,
						Component.translatable("crypto.memorypalace.verify_change"), this,
						(password, key) -> this.minecraft.gui.setScreen(
								PasswordScreen.newPassword(keyring, key, this, () -> this.minecraft.gui.setScreen(this)))))))
				.bounds(left, y, COLUMN, 20).build());
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
				.bounds(left, y + ROW, COLUMN, 20).build());
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
				.bounds(left, y + 2 * ROW, COLUMN, 20).build());
		// 忘记密码：用恢复密钥重置（原密码没有保存，无法找回）
		Button forgot = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.forgot_password"),
				b -> withKeyring(keyring -> {
					if (!hasRecovery(keyring)) {
						show("settings.memorypalace.no_recovery");
						return;
					}
					this.minecraft.gui.setScreen(PasswordScreen.recover(keyring, this, () -> this.minecraft.gui.setScreen(this)));
				}))
				.bounds(left, y + 3 * ROW, COLUMN, 20).build());

		// 实体钥匙（YubiKey 等）：只支持 Windows。没登记就登记一把，登记过就问要不要移除
		boolean keySupported = SecurityKey.available();
		Button token = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.token"),
				b -> withKeyring(this::tokenClicked))
				.bounds(right, y, COLUMN, 20)
				.tooltip(Tooltip.create(Component.translatable(keySupported
						? "settings.memorypalace.token_tooltip" : "settings.memorypalace.token_unavailable"))).build());
		addRenderableWidget(Button.builder(autoLockLabel(), b -> {
			BookSettings.setAutoLock(BookSettings.next(BookSettings.autoLock()));
			b.setMessage(autoLockLabel());
		}).bounds(right, y + ROW, COLUMN, 20).build());
		addRenderableWidget(Button.builder(inboxEncryptionLabel(), b -> {
			BookSettings.setInboxEncryption(!BookSettings.inboxEncryption());
			b.setMessage(inboxEncryptionLabel());
		}).bounds(right, y + 2 * ROW, COLUMN, 20).build());
		// 信任新的收件箱公钥：并排显示记住的旧指纹和服务器上的新指纹，确认后才替换（只在别人的服务器上有用）
		Button trust = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.trust_box"),
				b -> withKeyring(this::confirmTrust))
				.bounds(right, y + 3 * ROW, COLUMN, 20).build());
		change.active = regen.active = remove.active = forgot.active = inWorld;
		trust.active = inWorld && !this.minecraft.hasSingleplayerServer();
		token.active = inWorld && keySupported;
	}

	// ---- 笔记库与备份 ----

	private void initVault() {
		int x = this.width / 2 - WIDE / 2;
		int y = contentTop();
		// 笔记库和备份都在本机硬盘上：只有单人游戏、自己开的局域网才能用
		Vault vault = this.minecraft.hasSingleplayerServer() ? PalaceServer.vault() : null;
		Path backups = vault != null ? PalaceServer.backupDir() : null;
		Component unavailable = Component.translatable("settings.memorypalace.local_only");
		Button reveal = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.open_folder"),
				b -> ExternalEditor.reveal(vault.root()))
				.bounds(x, y, WIDE, 20)
				.tooltip(Tooltip.create(vault != null ? Component.translatable("settings.memorypalace.open_folder_tooltip") : unavailable))
				.build());
		Button backupNow = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.backup_now"), b -> {
			b.active = false;
			show("settings.memorypalace.backup_running");
			PalaceServer.backupNow(ok -> this.minecraft.execute(() -> {
				b.active = true;
				show(ok ? "settings.memorypalace.backup_done" : "settings.memorypalace.backup_failed");
			}));
		}).bounds(x, y + ROW, WIDE, 20)
				.tooltip(Tooltip.create(backups != null ? Component.translatable("settings.memorypalace.backup_now_tooltip") : unavailable))
				.build());
		Button browse = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.backups"),
				b -> this.minecraft.gui.setScreen(new BackupScreen(this)))
				.bounds(x, y + 2 * ROW, WIDE, 20)
				.tooltip(Tooltip.create(backups != null ? Component.translatable("settings.memorypalace.backups_tooltip") : unavailable))
				.build());
		Button openBackups = addRenderableWidget(Button.builder(Component.translatable("settings.memorypalace.open_backups"), b -> {
			try {
				Files.createDirectories(backups);
			} catch (java.io.IOException e) {
				// 打不开就让文件管理器自己报错
			}
			ExternalEditor.reveal(backups);
		}).bounds(x, y + 3 * ROW, WIDE, 20)
				.tooltip(Tooltip.create(backups != null ? Component.translatable("settings.memorypalace.open_backups_tooltip") : unavailable))
				.build());
		reveal.active = vault != null;
		backupNow.active = browse.active = openBackups.active = backups != null;
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

	private void tokenClicked(String keyring) {
		KeyRing.Token registered;
		try {
			registered = KeyRing.token(keyring);
		} catch (RuntimeException e) {
			show("crypto.memorypalace.error");
			return;
		}
		if (registered == null) {
			this.minecraft.gui.setScreen(PasswordScreen.verify(keyring, Component.translatable("crypto.memorypalace.verify_token"), this,
					(password, key) -> registerToken(keyring, key)));
			return;
		}
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (!yes) {
				this.minecraft.gui.setScreen(this);
				return;
			}
			this.minecraft.gui.setScreen(PasswordScreen.verify(keyring, Component.translatable("crypto.memorypalace.verify_remove_token"), this,
					(password, key) -> writeAndShow(KeyRing.withoutToken(keyring), keyring, "settings.memorypalace.token_removed")));
		}, Component.translatable("settings.memorypalace.token_remove_title"),
				Component.translatable("settings.memorypalace.token_remove_message"),
				Component.translatable("settings.memorypalace.token_remove_yes"), CommonComponents.GUI_CANCEL));
	}

	/**
	 * 登记实体钥匙：在钥匙上建凭据，再让钥匙对新的盐算一次（两次都要玩家在 Windows 对话框里操作），
	 * 用结果锁一份主密钥写回钥匙圈。Windows 的调用会一直等到玩家操作完，所以放在后台线程。
	 */
	private void registerToken(String keyring, MasterKey key) {
		MasterKey copy = new MasterKey(key.bytes()); // 回调结束后原来那把可能被上锁销毁：自己留一份，用完销毁
		this.minecraft.gui.setScreen(this);
		show("settings.memorypalace.token_registering");
		Thread.ofPlatform().name("memorypalace-security-key").daemon(true).start(() -> {
			String content = null;
			Component failure = null;
			try {
				byte[] credential = SecurityKey.register();
				byte[] salt = KeyRing.newTokenSalt();
				byte[] output = SecurityKey.prf(credential, salt);
				try {
					content = KeyRing.withToken(keyring, copy, credential, salt, output);
					KeyRing.unlockWithToken(content, output).destroy(); // 存之前先确认用它真能解开
				} finally {
					java.util.Arrays.fill(output, (byte) 0);
				}
			} catch (SecurityKey.Failure e) {
				failure = e.message();
			} catch (Exception e) {
				com.memorypalace.MemoryPalace.LOGGER.error("登记实体钥匙失败", e);
				failure = Component.translatable("message.memorypalace.token_failed");
			} finally {
				copy.destroy();
			}
			String result = content;
			Component error = failure;
			this.minecraft.execute(() -> {
				if (result == null) {
					message = error;
					return;
				}
				writeAndShow(result, keyring, "settings.memorypalace.token_added");
			});
		});
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

	private static Component autoSaveLabel() {
		return Component.translatable("settings.memorypalace.autosave", BookSettings.label(BookSettings.autoSave()));
	}

	private static Component inboxEncryptionLabel() {
		return Component.translatable("settings.memorypalace.inbox_encryption", Component.translatable(
				BookSettings.inboxEncryption() ? "settings.memorypalace.on" : "settings.memorypalace.off"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, tabsTop() - 16, 0xFFFFFFFF);
		int below = contentTop() + 4 * ROW + 2;
		if (tab == Tab.READING) {
			// 按键一览（冲突的标红）
			graphics.centeredText(this.font, keySummary(), this.width / 2, below, 0xFFA0A0A0);
		}
		graphics.centeredText(this.font, message, this.width / 2, below + 14, 0xFFFFE080);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}
