package com.memorypalace.client.crypto;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.crypto.MasterKey;
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

/**
 * 输密码的通用界面。各种用途由工厂类拼出来：VaultPasswords（钥匙圈）、FolderPasswords（文件夹密码）、
 * ReadPasswordPrompts（单篇阅读密码）。
 * 密码按"•"显示；Argon2 算一次要半秒左右，放到后台线程做，界面不卡。
 * 后台算完时如果已经取消 / 上锁 / 离开世界（"代次"变了），结果作废并销毁，不会迟到地把它解锁。
 */
public class PasswordScreen extends Screen {
	/** 在后台线程检查输入，返回错误提示（null = 成功）。values 用完由界面清零。 */
	interface Check {
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
	private final Check keyCheck;     // null = 不显示"使用实体钥匙"；不用输入框，values 是空的

	private EditBox[] fields;
	private Button okButton;
	private Component status = Component.empty();
	private boolean busy;

	PasswordScreen(Component title, Component message, Component[] hints, boolean masked, Screen returnTo,
			Check check, Runnable onSuccess, Runnable onAbandon, Runnable onForgot) {
		this(title, message, hints, masked, returnTo, check, onSuccess, onAbandon, onForgot, null);
	}

	PasswordScreen(Component title, Component message, Component[] hints, boolean masked, Screen returnTo,
			Check check, Runnable onSuccess, Runnable onAbandon, Runnable onForgot, Check keyCheck) {
		super(title);
		this.keyCheck = keyCheck;
		this.message = message;
		this.hints = hints;
		this.masked = masked;
		this.returnTo = returnTo;
		this.check = check;
		this.onSuccess = onSuccess;
		this.onAbandon = onAbandon;
		this.onForgot = onForgot;
	}

	/** 服务端核对没通过（密码不对、要等一会儿）：解开按钮，显示原因，可以重输。 */
	void rejected(Component why) {
		busy = false;
		if (okButton != null) {
			okButton.active = true;
		}
		status = why;
	}

	static Component checkNewPassword(char[][] values) {
		if (values[0].length < KeyRing.MIN_PASSWORD) {
			return t("too_short", KeyRing.MIN_PASSWORD);
		}
		if (!Arrays.equals(values[0], values[1])) {
			return t("mismatch");
		}
		return null;
	}

	static boolean safeHasRecovery(String keyring) {
		try {
			return KeyRing.hasRecovery(keyring);
		} catch (RuntimeException e) {
			return false;
		}
	}

	static void destroy(MasterKey key) {
		if (key != null) {
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
		int extraY = buttonsY + 24;
		if (onForgot != null) {
			addRenderableWidget(Button.builder(t("forgot"), b -> onForgot.run()).bounds(x, extraY, 200, 20).build());
			extraY += 24;
		}
		if (keyCheck != null) {
			keyButton = addRenderableWidget(Button.builder(t("use_token"), b -> useKey()).bounds(x, extraY, 200, 20).build());
			keyButton.active = !busy;
		}
	}

	private Button keyButton;

	/** 用实体钥匙解锁：不用输入框，Windows 会弹出对话框引导插钥匙、输 PIN、触摸。 */
	private void useKey() {
		if (busy) {
			return;
		}
		run(keyCheck, new char[0][], t("token_waiting"));
	}

	/** 成功后还在等服务端确认（比如钥匙圈存盘）：锁住按钮并显示提示。 */
	void waitFor(Component what) {
		busy = true;
		if (okButton != null) {
			okButton.active = false;
		}
		status = what;
	}

	/** 打开前就要显示的提示（比如上次密码不对）。 */
	void showStatus(Component what) {
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
		run(check, values, t("checking"));
	}

	/** 在后台线程跑 check（values 用完清零），跑完回到渲染线程显示结果或执行 onSuccess。 */
	private void run(Check check, char[][] values, Component waiting) {
		busy = true;
		okButton.active = false;
		if (keyButton != null) {
			keyButton.active = false;
		}
		status = waiting;
		int generation = ClientKeys.generation();
		// 实体钥匙要等 Windows 的对话框，可能好几十秒：用普通线程，别占着虚拟线程的载体
		Thread.ofPlatform().daemon(true).name("memorypalace-password").start(() -> {
			Component error;
			try {
				error = check.run(values);
			} catch (SecurityKey.Failure e) {
				error = e.message();
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
				if (keyButton != null) {
					keyButton.active = true;
				}
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
		int statusY = top + hints.length * 24 + 34 + (onForgot != null ? 24 : 0) + (keyCheck != null ? 24 : 0);
		graphics.centeredText(this.font, status, this.width / 2, statusY, 0xFFFF8080);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
