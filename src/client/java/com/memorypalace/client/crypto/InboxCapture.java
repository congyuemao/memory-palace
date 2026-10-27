package com.memorypalace.client.crypto;

import com.memorypalace.client.QuickCaptureScreen;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.mc.ToolPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.time.OffsetDateTime;

/**
 * 快速收集（按 K）的发送：随手记不用输密码也能加密——用钥匙圈里的收件箱公钥加密，服务端只收到密文。
 * - 设置里关了"收件箱加密"、或者还没设过密码（没有钥匙圈）、钥匙圈里还没有收件箱钥匙：照旧发明文，并提示一次怎么开启
 * - 公钥指纹和本机记住的对不上（见 BoxPins）：两步确认，默认都是安全的那个选项；取消时这条随手记回到输入框里，不丢
 * 只在渲染线程使用。
 */
public final class InboxCapture {
	// 这次连接里已经提示过"随手记没加密"了：不每条都提示
	private static boolean hinted;

	private InboxCapture() {}

	/** 离开世界时调用。 */
	public static void reset() {
		hinted = false;
	}

	/** 发送一条随手记。text 不为空白。 */
	public static void send(String text) {
		OffsetDateTime now = OffsetDateTime.now();
		if (!BookSettings.inboxEncryption()) {
			sendPlain(text, now);
			return;
		}
		// 公钥是明文存在钥匙圈里的，不需要密码；读钥匙圈失败时 ClientKeys 会提示，这条不发（不悄悄改发明文）
		ClientKeys.withKeyring(keyring -> {
			byte[] publicKey = boxPublic(keyring);
			if (publicKey == null) {
				sendPlain(text, now);
				hint(keyring.isEmpty() ? "message.memorypalace.inbox_plain_no_keyring" : "message.memorypalace.inbox_plain_no_box");
				return;
			}
			String fingerprint = InboxCipher.fingerprint(publicKey);
			if (BoxPins.check(fingerprint) == BoxPins.Result.MISMATCH) {
				confirmMismatch(text, now, publicKey);
			} else {
				sendSealed(text, now, publicKey);
			}
		});
	}

	private static byte[] boxPublic(String keyring) {
		if (keyring.isEmpty()) {
			return null;
		}
		try {
			return KeyRing.boxPublic(keyring);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void sendPlain(String text, OffsetDateTime now) {
		// 带上本机的本地时间和时区："今天"按玩家所在地算（出国、跨日期变更线都对）
		ClientPlayNetworking.send(new ToolPayloads.Capture(text, now.toString()));
	}

	private static void sendSealed(String text, OffsetDateTime now, byte[] publicKey) {
		String line;
		try {
			line = InboxCipher.seal(publicKey, Inbox.entryLine(now, text));
		} catch (IllegalArgumentException e) {
			// 空内容、内容太长（加密后超过一行的上限）、公钥坏了：不发，告诉玩家
			PasswordScreen.tell("message.memorypalace.capture_failed");
			return;
		}
		ClientPlayNetworking.send(new ToolPayloads.CaptureSealed(line, now.toString()));
	}

	private static void hint(String key) {
		if (!hinted) {
			hinted = true;
			PasswordScreen.tell(key);
		}
	}

	/**
	 * 公钥对不上：
	 * 1. "公钥无法对应"，列出常见的正常原因 → 取消保存 / 继续保存
	 * 2. "可能造成攻击者获取加密信息，确认继续保存吗？" → 否 / 是（高危操作！，红色）
	 * 选"是"只保存这一条，不改记住的指纹，下次还会提示；要长期信任新公钥，去设置里的"信任新公钥"。
	 * 取消（含按 Esc）都回到输入框，这条随手记还在。
	 */
	private static void confirmMismatch(String text, OffsetDateTime now, byte[] publicKey) {
		Minecraft mc = Minecraft.getInstance();
		Runnable backToInput = () -> mc.gui.setScreen(new QuickCaptureScreen(text));
		mc.gui.setScreen(new ConfirmScreen(proceed -> {
			if (!proceed) {
				backToInput.run();
				return;
			}
			mc.gui.setScreen(new ConfirmScreen(really -> {
				if (really) {
					sendSealed(text, now, publicKey);
					mc.gui.setScreen(null);
				} else {
					backToInput.run();
				}
			}, Component.translatable("screen.memorypalace.box_mismatch.title"),
					Component.translatable("screen.memorypalace.box_mismatch.confirm"),
					Component.translatable("screen.memorypalace.box_mismatch.yes").withStyle(ChatFormatting.RED),
					Component.translatable("screen.memorypalace.box_mismatch.no")));
		}, Component.translatable("screen.memorypalace.box_mismatch.title"),
				Component.translatable("screen.memorypalace.box_mismatch.message", BoxPins.pinned(), InboxCipher.fingerprint(publicKey)),
				Component.translatable("screen.memorypalace.box_mismatch.continue"),
				Component.translatable("screen.memorypalace.box_mismatch.cancel")));
	}
}
