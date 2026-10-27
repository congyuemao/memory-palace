package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.NoteEnvelope;
import com.memorypalace.core.crypto.WrongKeyException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 输密码的界面，一个界面多种用途（见下面的静态工厂方法）：首次设置、解锁、用恢复密钥重置、改密码、验证本人、
 * 文件夹密码、单篇阅读密码。
 * 密码按"•"显示；Argon2 算一次要半秒左右，放到后台线程做，界面不卡。
 * 后台算完时如果已经取消 / 上锁 / 离开世界（"代次"变了），结果作废并销毁，不会迟到地把它解锁。
 */
public class PasswordScreen extends Screen {
	/** 在后台线程检查输入，返回错误提示（null = 成功）。values 用完由界面清零。 */
	private interface Check {
		Component run(char[][] values) throws Exception;
	}

	private final Component message;
	private final Component[] hints;
	private final boolean masked;
	private final Screen returnTo;
	private final Check check;
	private final Runnable onSuccess;
	private final Runnable onAbandon; // 结果作废时销毁临时密钥等
	private final Runnable onForgot;  // null = 不显示"忘记密码"

	private EditBox[] fields;
	private Button okButton;
	private Component status = Component.empty();
	private boolean busy;

	private PasswordScreen(Component title, Component message, Component[] hints, boolean masked, Screen returnTo,
			Check check, Runnable onSuccess, Runnable onAbandon, Runnable onForgot) {
		super(title);
		this.message = message;
		this.hints = hints;
		this.masked = masked;
		this.returnTo = returnTo;
		this.check = check;
		this.onSuccess = onSuccess;
		this.onAbandon = onAbandon;
		this.onForgot = onForgot;
	}

	// ---- 五种用途 ----

	/**
	 * 第一次启用加密：设密码（输两遍）→ 生成主密钥 → 等服务端确认钥匙圈已存盘 → 显示恢复密钥（可以不使用）→ 继续 then。
	 * 钥匙圈没存成功就放弃，绝不用一个没存下来的主密钥去加密笔记。
	 */
	public static PasswordScreen setup(Screen returnTo, Runnable then) {
		AtomicReference<KeyRing.Created> created = new AtomicReference<>();
		AtomicReference<char[]> password = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		PasswordScreen screen = new PasswordScreen(t("setup_title"), t("setup_message", KeyRing.MIN_PASSWORD),
				new Component[]{t("password"), t("password_again")}, true, returnTo,
				values -> {
					Component error = checkNewPassword(values);
					if (error != null) {
						return error;
					}
					created.set(KeyRing.create(values[0], true));
					password.set(values[0].clone()); // 选择"不使用恢复密钥"时还要用一次
					return null;
				},
				() -> {
					KeyRing.Created c = created.get();
					self.get().waitFor(t("saving_keyring"));
					ClientKeys.writeKeyring(c.content(), "", ok -> {
						if (!ok) {
							c.key().destroy();
							wipe(password.get());
							Minecraft.getInstance().gui.setScreen(returnTo);
							return;
						}
						ClientKeys.unlockedWith(c.content(), c.key());
						Minecraft.getInstance().gui.setScreen(new RecoveryKeyScreen(c.recoveryKey(),
								() -> {
									wipe(password.get());
									then.run();
								},
								() -> {
									// 不使用恢复密钥：把它从钥匙圈里删掉（等确认存盘）
									try {
										String without = KeyRing.removeRecovery(c.content(), password.get());
										ClientKeys.writeKeyring(without, c.content(), removed -> then.run());
									} catch (Exception e) {
										MemoryPalace.LOGGER.error("删除恢复密钥失败");
										then.run();
									} finally {
										wipe(password.get());
									}
								}));
					});
				},
				() -> {
					if (created.get() != null) {
						created.get().key().destroy();
					}
					wipe(password.get());
				}, null);
		self.set(screen);
		return screen;
	}

	/**
	 * 给文件夹设独立密码：设密码（输两遍）→ 生成文件夹钥匙 → 等服务端确认锁文件已存盘 → 显示恢复密钥（可以不使用）→ onLocked。
	 * 锁没存成功就放弃，绝不用一个没存下来的文件夹钥匙去加密笔记。onLocked 拿到的 key 由调用方保管。
	 */
	public static PasswordScreen setupFolder(String folder, Screen returnTo, java.util.function.Consumer<KeyRing.Created> onLocked) {
		AtomicReference<KeyRing.Created> created = new AtomicReference<>();
		AtomicReference<char[]> password = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		PasswordScreen screen = new PasswordScreen(t("folder_setup_title", folder), t("folder_setup_message", KeyRing.MIN_PASSWORD),
				new Component[]{t("password"), t("password_again")}, true, returnTo,
				values -> {
					Component error = checkNewPassword(values);
					if (error != null) {
						return error;
					}
					created.set(KeyRing.createFolder(values[0], true));
					password.set(values[0].clone()); // 选择"不使用恢复密钥"时还要用一次
					return null;
				},
				() -> {
					KeyRing.Created c = created.get();
					self.get().waitFor(t("saving_folder_lock"));
					FolderKeys.writeLock(folder, c.content(), "", ok -> {
						if (!ok) {
							c.key().destroy();
							wipe(password.get());
							Minecraft.getInstance().gui.setScreen(returnTo);
							return;
						}
						Minecraft.getInstance().gui.setScreen(new RecoveryKeyScreen(c.recoveryKey(),
								() -> {
									wipe(password.get());
									onLocked.accept(c);
								},
								() -> {
									// 不使用恢复密钥：把它从锁里删掉（等确认存盘）
									try {
										String without = KeyRing.removeRecovery(c.content(), password.get());
										FolderKeys.writeLock(folder, without, c.content(), removed -> onLocked.accept(c));
									} catch (Exception e) {
										MemoryPalace.LOGGER.error("删除文件夹恢复密钥失败");
										onLocked.accept(c);
									} finally {
										wipe(password.get());
									}
								}));
					});
				},
				() -> {
					if (created.get() != null) {
						created.get().key().destroy();
					}
					wipe(password.get());
				}, null);
		self.set(screen);
		return screen;
	}

	/**
	 * 解锁一个文件夹：输文件夹密码。onKey 拿到文件夹钥匙，由调用方保管（FolderKeys）。
	 * 锁里有恢复密钥时显示"忘记密码？"：用恢复密钥设新密码后同样交给 onKey。
	 */
	public static PasswordScreen unlockFolder(String folder, String lock, Screen returnTo, java.util.function.Consumer<MasterKey> onKey) {
		return verifyFolder(folder, lock, t("folder_unlock_message"), returnTo, onKey, onKey);
	}

	/**
	 * 输入当前文件夹密码证明是本人（改密码、解除文件夹密码之前），onVerified 拿到文件夹钥匙。
	 * 忘记密码：用恢复密钥设一个新密码（存好之后）→ onReset 拿到文件夹钥匙。
	 */
	public static PasswordScreen verifyFolder(String folder, String lock, Component why, Screen returnTo,
			java.util.function.Consumer<MasterKey> onVerified, java.util.function.Consumer<MasterKey> onReset) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		return new PasswordScreen(t("folder_unlock_title", folder), why, new Component[]{t("password")}, true, returnTo,
				values -> {
					key.set(KeyRing.unlock(lock, values[0]));
					return null;
				},
				() -> onVerified.accept(key.get()),
				() -> destroy(key.get()),
				safeHasRecovery(lock) ? () -> Minecraft.getInstance().gui.setScreen(recoverFolder(folder, lock, returnTo, onReset)) : null);
	}

	/** 忘记文件夹密码：输恢复密钥 → 设新密码（存好锁）→ onDone 拿到文件夹钥匙。原密码没有保存，只能重置。 */
	public static PasswordScreen recoverFolder(String folder, String lock, Screen returnTo, java.util.function.Consumer<MasterKey> onDone) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		return new PasswordScreen(t("folder_recover_title", folder), t("recover_message"), new Component[]{t("recovery_key")}, false, returnTo,
				values -> {
					key.set(KeyRing.unlockWithRecovery(lock, new String(values[0])));
					return null;
				},
				() -> Minecraft.getInstance().gui.setScreen(newFolderPassword(folder, lock, key.get(), returnTo, onDone)),
				() -> destroy(key.get()), null);
	}

	/**
	 * 给文件夹设新密码（已经用旧密码或恢复密钥证明过身份，key 是文件夹钥匙）。恢复密钥保持不变。
	 * 等服务端确认锁存好后才算改好（onDone 拿到 key）；取消或失败时销毁 key。
	 */
	public static PasswordScreen newFolderPassword(String folder, String lock, MasterKey key, Screen returnTo,
			java.util.function.Consumer<MasterKey> onDone) {
		AtomicReference<String> content = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
		PasswordScreen screen = new PasswordScreen(t("folder_new_password_title", folder), t("folder_setup_message", KeyRing.MIN_PASSWORD),
				new Component[]{t("password"), t("password_again")}, true, returnTo,
				values -> {
					Component error = checkNewPassword(values);
					if (error != null) {
						return error;
					}
					content.set(KeyRing.changePassword(lock, key, values[0]));
					return null;
				},
				() -> {
					self.get().waitFor(t("saving_folder_lock"));
					FolderKeys.writeLock(folder, content.get(), lock, ok -> {
						if (!ok) {
							key.destroy();
							Minecraft.getInstance().gui.setScreen(returnTo);
							return;
						}
						done.set(true);
						onDone.accept(key);
					});
				},
				key::destroy, null) {
			@Override
			public void onClose() {
				if (!done.get()) {
					key.destroy(); // 取消改密码：解出来的文件夹钥匙不能留在内存里
				}
				super.onClose();
			}
		};
		self.set(screen);
		return screen;
	}

	/** 解锁：输密码。钥匙圈里有恢复密钥时显示"忘记密码？"。 */
	public static PasswordScreen unlock(String keyring, Screen returnTo, Runnable then) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		boolean hasRecovery = safeHasRecovery(keyring);
		return new PasswordScreen(t("unlock_title"), t("unlock_message"), new Component[]{t("password")}, true, returnTo,
				values -> {
					key.set(KeyRing.unlock(keyring, values[0]));
					return null;
				},
				() -> {
					ClientKeys.unlockedWith(keyring, key.get());
					then.run();
				},
				() -> destroy(key.get()),
				hasRecovery ? () -> Minecraft.getInstance().gui.setScreen(recover(keyring, returnTo, then)) : null);
	}

	/** 忘记密码：输恢复密钥 → 设新密码 → 继续 then。原密码没有保存，只能重置。 */
	public static PasswordScreen recover(String keyring, Screen returnTo, Runnable then) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		return new PasswordScreen(t("recover_title"), t("recover_message"), new Component[]{t("recovery_key")}, false, returnTo,
				values -> {
					key.set(KeyRing.unlockWithRecovery(keyring, new String(values[0])));
					return null;
				},
				() -> Minecraft.getInstance().gui.setScreen(newPassword(keyring, key.get(), returnTo, then)),
				() -> destroy(key.get()), null);
	}

	/**
	 * 设新密码（已经用旧密码或恢复密钥证明过身份，key 是解锁出来的主密钥）。恢复密钥保持不变。
	 * 等服务端确认存盘后才算改好；取消或失败时销毁 key（除非它就是当前正在用的那把）。
	 */
	public static PasswordScreen newPassword(String keyring, MasterKey key, Screen returnTo, Runnable then) {
		AtomicReference<String> content = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		PasswordScreen screen = new PasswordScreen(t("new_password_title"), t("setup_message", KeyRing.MIN_PASSWORD),
				new Component[]{t("password"), t("password_again")}, true, returnTo,
				values -> {
					Component error = checkNewPassword(values);
					if (error != null) {
						return error;
					}
					content.set(KeyRing.changePassword(keyring, key, values[0]));
					return null;
				},
				() -> {
					self.get().waitFor(t("saving_keyring"));
					ClientKeys.writeKeyring(content.get(), keyring, ok -> {
						if (!ok) {
							destroyUnlessCurrent(key);
							Minecraft.getInstance().gui.setScreen(returnTo);
							return;
						}
						ClientKeys.unlockedWith(content.get(), key);
						tell("message.memorypalace.password_changed");
						then.run();
					});
				},
				() -> destroyUnlessCurrent(key), null) {
			@Override
			public void onClose() {
				destroyUnlessCurrent(key); // 取消改密码：恢复流程解出来的那把钥匙不能留在内存里
				super.onClose();
			}
		};
		self.set(screen);
		return screen;
	}

	/**
	 * 验证本人（解除加密、改密码、换/删恢复密钥之前）：即使已经解锁也要重新输一次。
	 * onVerified 在渲染线程收到密码副本和主密钥；密码副本在回调返回后被清零，回调里要用就当场用完。
	 */
	public static PasswordScreen verify(String keyring, Component why, Screen returnTo, BiConsumer<char[], MasterKey> onVerified) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		AtomicReference<char[]> password = new AtomicReference<>();
		return new PasswordScreen(t("verify_title"), why, new Component[]{t("password")}, true, returnTo,
				values -> {
					key.set(KeyRing.unlock(keyring, values[0]));
					password.set(values[0].clone());
					return null;
				},
				() -> {
					try {
						ClientKeys.unlockedWith(keyring, key.get());
						onVerified.accept(password.get(), key.get());
					} finally {
						wipe(password.get());
					}
				},
				() -> {
					destroy(key.get());
					wipe(password.get());
				}, null);
	}

	/**
	 * 读别人设了阅读密码的笔记：输密码 → 后台用 derive 算出钥匙 → onKeys（交给服务端核对，界面停在"正在核对"，
	 * 结果回来时由 ReadPasswords 关掉或调用 rejected）。status 是一开始就显示的提示（比如上次密码不对）。
	 * onCancel：玩家取消时调用。
	 */
	public static PasswordScreen readPassword(Component status, java.util.function.Function<char[], NoteEnvelope.PasswordKeys> derive,
			java.util.function.Consumer<NoteEnvelope.PasswordKeys> onKeys, Runnable onCancel) {
		AtomicReference<NoteEnvelope.PasswordKeys> keys = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		PasswordScreen screen = new PasswordScreen(t("read_title"), t("read_message"), new Component[]{t("password")}, true, null,
				values -> {
					keys.set(derive.apply(values[0]));
					return null;
				},
				() -> {
					self.get().waitFor(t("read_checking"));
					onKeys.accept(keys.getAndSet(null));
				},
				() -> {
					NoteEnvelope.PasswordKeys k = keys.getAndSet(null);
					if (k != null) {
						k.wipe();
					}
				}, null) {
			@Override
			public void onClose() {
				onCancel.run(); // 取消（含"正在核对"时按 Esc）：发出去的那次作废
				super.onClose();
			}
		};
		screen.status = status;
		self.set(screen);
		return screen;
	}

	/**
	 * 给自己的一篇加密笔记设（或换）阅读密码：输两遍 → 后台生成密码锁 → onLock（调用方重新加密、存好后关掉界面）。
	 * preview 是真正解出来的正文开头：标题是服务端说的，恶意服务端可能把别的笔记换进来，让玩家看一眼到底要分享的是什么。
	 * 取消时什么都不改。
	 */
	public static PasswordScreen newReadPassword(String title, String preview, java.util.function.Consumer<NoteEnvelope.PasswordLock> onLock) {
		AtomicReference<NoteEnvelope.PasswordLock> lock = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		PasswordScreen screen = new PasswordScreen(t("read_setup_title", title), t("read_setup_message", KeyRing.MIN_PASSWORD, preview),
				new Component[]{t("password"), t("password_again")}, true, null,
				values -> {
					Component error = checkNewPassword(values);
					if (error != null) {
						return error;
					}
					lock.set(NoteEnvelope.newPasswordLock(values[0]));
					return null;
				},
				() -> {
					self.get().waitFor(t("saving_note"));
					onLock.accept(lock.getAndSet(null));
				},
				() -> {
					NoteEnvelope.PasswordLock l = lock.getAndSet(null);
					if (l != null) {
						Arrays.fill(l.wrappingKey(), (byte) 0);
					}
				}, null);
		self.set(screen);
		return screen;
	}

	/** 服务端核对没通过（密码不对、要等一会儿）：解开按钮，显示原因，可以重输。 */
	void rejected(Component why) {
		busy = false;
		if (okButton != null) {
			okButton.active = true;
		}
		status = why;
	}

	private static Component checkNewPassword(char[][] values) {
		if (values[0].length < KeyRing.MIN_PASSWORD) {
			return t("too_short", KeyRing.MIN_PASSWORD);
		}
		if (!Arrays.equals(values[0], values[1])) {
			return t("mismatch");
		}
		return null;
	}

	private static boolean safeHasRecovery(String keyring) {
		try {
			return KeyRing.hasRecovery(keyring);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static void destroy(MasterKey key) {
		if (key != null) {
			key.destroy();
		}
	}

	private static void destroyUnlessCurrent(MasterKey key) {
		if (key != null && !(ClientKeys.unlocked() && ClientKeys.key() == key)) {
			key.destroy();
		}
	}

	static Component t(String key, Object... args) {
		return Component.translatable("crypto.memorypalace." + key, args);
	}

	static void tell(String key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.translatable(key));
		}
	}

	static void wipe(char[] chars) {
		if (chars != null) {
			Arrays.fill(chars, '\0');
		}
	}

	// ---- 界面 ----

	/**
	 * 密码输入框：显示成"•"，旁白（屏幕朗读）只念"密码输入框"不念内容，禁止复制和剪切。
	 */
	private static final class PasswordBox extends EditBox {
		PasswordBox(Font font, int x, int y, int w, int h, Component label) {
			super(font, x, y, w, h, label);
			addFormatter((text, start) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
		}

		@Override
		protected MutableComponent createNarrationMessage() {
			return t("password_narration").copy();
		}

		@Override
		public void updateWidgetNarration(NarrationElementOutput output) {
			output.add(NarratedElementType.TITLE, t("password_narration"));
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.isCopy() || event.isCut()) {
				return true;
			}
			return super.keyPressed(event);
		}
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 10;
		fields = new EditBox[hints.length];
		for (int i = 0; i < hints.length; i++) {
			EditBox box = masked ? new PasswordBox(this.font, x, y + i * 24, 200, 20, hints[i])
					: new EditBox(this.font, x, y + i * 24, 200, 20, hints[i]);
			box.setMaxLength(128);
			box.setHint(hints[i]);
			fields[i] = addRenderableWidget(box);
		}
		setInitialFocus(fields[0]);
		int buttonsY = y + hints.length * 24 + 8;
		okButton = addRenderableWidget(Button.builder(CommonComponents.GUI_OK, b -> submit()).bounds(x, buttonsY, 98, 20).build());
		okButton.active = !busy;
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(x + 102, buttonsY, 98, 20).build());
		if (onForgot != null) {
			addRenderableWidget(Button.builder(t("forgot"), b -> onForgot.run()).bounds(x, buttonsY + 24, 200, 20).build());
		}
	}

	/** 成功后还在等服务端确认（比如钥匙圈存盘）：锁住按钮并显示提示。 */
	private void waitFor(Component what) {
		busy = true;
		if (okButton != null) {
			okButton.active = false;
		}
		status = what;
	}

	private void submit() {
		if (busy) {
			return;
		}
		char[][] values = new char[fields.length][];
		for (int i = 0; i < fields.length; i++) {
			values[i] = fields[i].getValue().toCharArray();
			fields[i].setValue("");
		}
		busy = true;
		okButton.active = false;
		status = t("checking");
		int generation = ClientKeys.generation();
		Thread.ofVirtual().name("memorypalace-password").start(() -> {
			Component error;
			try {
				error = check.run(values);
			} catch (WrongKeyException e) {
				error = t(masked ? "wrong_password" : "wrong_recovery");
			} catch (IllegalArgumentException e) {
				error = t("bad_input");
			} catch (Exception e) {
				MemoryPalace.LOGGER.error("密码处理失败：{}", e.getClass().getSimpleName());
				error = t("error");
			} finally {
				for (char[] v : values) {
					wipe(v);
				}
			}
			Component result = error;
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				// 这段时间里取消了、上锁了或离开了世界：结果作废
				if (generation != ClientKeys.generation() || mc.gui.screen() != this) {
					onAbandon.run();
					return;
				}
				busy = false;
				okButton.active = true;
				if (result == null) {
					status = Component.empty();
					onSuccess.run();
				} else {
					status = result;
				}
			});
		});
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && !busy) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(returnTo);
	}

	@Override
	public void removed() {
		// 离开界面时把输入框清空，别让密码留在控件里
		if (fields != null) {
			for (EditBox f : fields) {
				f.setValue("");
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = this.height / 2 - 10;
		// 屏幕够宽就放宽一点，少换行；文案里的 "\n" 会强制换行（按句子分行，不在句子中间断开）
		var lines = this.font.split(message, Math.min(this.width - 40, 420));
		int y = top - 28 - lines.size() * 10;
		graphics.centeredText(this.font, this.title, this.width / 2, y - 14, 0xFFFFFFFF);
		for (var line : lines) {
			graphics.centeredText(this.font, line, this.width / 2, y, 0xFFC0C0C0);
			y += 10;
		}
		int statusY = top + hints.length * 24 + (onForgot != null ? 58 : 34);
		graphics.centeredText(this.font, status, this.width / 2, statusY, 0xFFFF8080);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
