package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
import com.memorypalace.core.crypto.WrongKeyException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static com.memorypalace.client.crypto.PasswordScreen.checkNewPassword;
import static com.memorypalace.client.crypto.PasswordScreen.destroy;
import static com.memorypalace.client.crypto.PasswordScreen.safeHasRecovery;
import static com.memorypalace.client.crypto.PasswordScreen.t;
import static com.memorypalace.client.crypto.PasswordScreen.tell;
import static com.memorypalace.client.crypto.PasswordScreen.wipe;

/** 钥匙圈（主密码）的几种密码界面：首次设置、解锁、用恢复密钥重置、改密码、验证本人。 */
public final class VaultPasswords {
	private VaultPasswords() {
	}

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
	 * 解锁：输密码。钥匙圈里有恢复密钥时显示"忘记密码？"；登记过实体钥匙（而且这台电脑能用）时显示"使用实体钥匙"。
	 */
	public static PasswordScreen unlock(String keyring, Screen returnTo, Runnable then) {
		AtomicReference<MasterKey> key = new AtomicReference<>();
		boolean hasRecovery = safeHasRecovery(keyring);
		KeyRing.Token token = safeToken(keyring);
		PasswordScreen.Check byKey = token == null || !SecurityKey.available() ? null : values -> {
			byte[] output = SecurityKey.prf(token.credentialId(), token.salt());
			try {
				key.set(KeyRing.unlockWithToken(keyring, output));
			} catch (WrongKeyException e) {
				return t("token_wrong");
			} finally {
				Arrays.fill(output, (byte) 0);
			}
			return null;
		};
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
				hasRecovery ? () -> Minecraft.getInstance().gui.setScreen(recover(keyring, returnTo, then)) : null,
				byKey);
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

	private static KeyRing.Token safeToken(String keyring) {
		try {
			return KeyRing.token(keyring);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void destroyUnlessCurrent(MasterKey key) {
		if (key != null && !(ClientKeys.unlocked() && ClientKeys.key() == key)) {
			key.destroy();
		}
	}
}
