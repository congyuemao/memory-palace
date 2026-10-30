package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.memorypalace.client.crypto.PasswordScreen.checkNewPassword;
import static com.memorypalace.client.crypto.PasswordScreen.destroy;
import static com.memorypalace.client.crypto.PasswordScreen.safeHasRecovery;
import static com.memorypalace.client.crypto.PasswordScreen.t;
import static com.memorypalace.client.crypto.PasswordScreen.wipe;

/** 文件夹独立密码的几种密码界面：设置、解锁、验证本人、用恢复密钥重置、改密码。拿到的文件夹钥匙由调用方保管。 */
public final class FolderPasswords {
	private FolderPasswords() {
	}

	/**
	 * 给文件夹设独立密码：设密码（输两遍）→ 生成文件夹钥匙 → 等服务端确认锁文件已存盘 → 显示恢复密钥（可以不使用）→ onLocked。
	 * 锁没存成功就放弃，绝不用一个没存下来的文件夹钥匙去加密笔记。onLocked 拿到的 key 由调用方保管。
	 */
	public static PasswordScreen setupFolder(String folder, Screen returnTo, Consumer<KeyRing.Created> onLocked) {
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
	public static PasswordScreen unlockFolder(String folder, String lock, Screen returnTo, Consumer<MasterKey> onKey) {
		return verifyFolder(folder, lock, t("folder_unlock_message"), returnTo, onKey, onKey);
	}

	/**
	 * 输入当前文件夹密码证明是本人（改密码、解除文件夹密码之前），onVerified 拿到文件夹钥匙。
	 * 忘记密码：用恢复密钥设一个新密码（存好之后）→ onReset 拿到文件夹钥匙。
	 */
	public static PasswordScreen verifyFolder(String folder, String lock, Component why, Screen returnTo,
			Consumer<MasterKey> onVerified, Consumer<MasterKey> onReset) {
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
	public static PasswordScreen recoverFolder(String folder, String lock, Screen returnTo, Consumer<MasterKey> onDone) {
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
			Consumer<MasterKey> onDone) {
		AtomicReference<String> content = new AtomicReference<>();
		AtomicReference<PasswordScreen> self = new AtomicReference<>();
		AtomicBoolean done = new AtomicBoolean();
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
}
