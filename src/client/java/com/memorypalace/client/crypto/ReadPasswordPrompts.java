package com.memorypalace.client.crypto;

import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.NoteEnvelope;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.memorypalace.client.crypto.PasswordScreen.checkNewPassword;
import static com.memorypalace.client.crypto.PasswordScreen.t;

/** 单篇阅读密码的密码界面：读别人的笔记时输密码、给自己的笔记设阅读密码。 */
public final class ReadPasswordPrompts {
	private ReadPasswordPrompts() {
	}

	/**
	 * 读别人设了阅读密码的笔记：输密码 → 后台用 derive 算出钥匙 → onKeys（交给服务端核对，界面停在"正在核对"，
	 * 结果回来时由 ReadPasswords 关掉或调用 rejected）。status 是一开始就显示的提示（比如上次密码不对）。
	 * onCancel：玩家取消时调用。
	 */
	public static PasswordScreen readPassword(Component status, Function<char[], NoteEnvelope.PasswordKeys> derive,
			Consumer<NoteEnvelope.PasswordKeys> onKeys, Runnable onCancel) {
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
		screen.showStatus(status);
		self.set(screen);
		return screen;
	}

	/**
	 * 给自己的一篇加密笔记设（或换）阅读密码：输两遍 → 后台生成密码锁 → onLock（调用方重新加密、存好后关掉界面）。
	 * preview 是真正解出来的正文开头：标题是服务端说的，恶意服务端可能把别的笔记换进来，让玩家看一眼到底要分享的是什么。
	 * 取消时什么都不改。
	 */
	public static PasswordScreen newReadPassword(String title, String preview, Consumer<NoteEnvelope.PasswordLock> onLock) {
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
}
