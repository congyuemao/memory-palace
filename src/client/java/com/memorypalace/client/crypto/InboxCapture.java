package com.memorypalace.client.crypto;

import com.memorypalace.client.PalaceKeys;
import com.memorypalace.client.QuickCaptureScreen;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.core.crypto.InboxCipher;
import com.memorypalace.core.crypto.KeyRing;
import com.memorypalace.core.vault.CaptureOutbox;
import com.memorypalace.core.vault.Inbox;
import com.memorypalace.mc.ToolPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * 快速收集（按 K）的发送：随手记不用输密码也能加密——用钥匙圈里的收件箱公钥加密，服务端只收到密文。
 * - 设置里关了"收件箱加密"、或者还没设过密码（没有钥匙圈）、钥匙圈里还没有收件箱钥匙：照旧发明文，并提示一次怎么开启
 * - 公钥指纹和本机记住的对不上（见 BoxPins）：两步确认，默认都是安全的那个选项；取消时这条随手记回到输入框里，不丢
 * - 每条随手记都等服务端回对应的保存结果（见 CaptureOutbox）：读钥匙圈失败、加密失败（不会改发明文）、服务端拒写、
 *   等不到结果，这条都留着，按 K 找回原文重试；重试沿用同一个编号，服务端不会重复追加
 * 只在渲染线程使用。
 */
public final class InboxCapture {
	// 这次连接里已经提示过"随手记没加密"了：不每条都提示
	private static boolean hinted;
	private static final CaptureOutbox OUTBOX = new CaptureOutbox();

	private InboxCapture() {}

	/** 离开世界时调用：还没结果的随手记都留着等找回（只在内存里）。 */
	public static void disconnect() {
		hinted = false;
		OUTBOX.disconnect();
	}

	/** 进入世界时调用：上次断开时还有没保存的随手记，提醒一下可以找回。 */
	public static void remind() {
		int failed = OUTBOX.failedCount(where());
		if (failed > 0) {
			say(Component.translatable("message.memorypalace.capture_pending", failed, PalaceKeys.capture.getTranslatedKeyMessage()));
		}
	}

	/** 按 K：这个世界里有没保存成功的随手记就先找回它，否则打开空白的输入框。 */
	public static QuickCaptureScreen screen() {
		return new QuickCaptureScreen(OUTBOX.nextFailed(where()));
	}

	/** 找回界面里改了但没发（按了 Esc）。 */
	public static void keepEdit(CaptureOutbox.Draft draft, String text) {
		OUTBOX.keepEdit(draft, text);
	}

	/** 找回界面里清空后回车：放弃这条。 */
	public static void discard(CaptureOutbox.Draft draft) {
		OUTBOX.discard(draft);
	}

	/** 每个客户端刻调用：等太久没结果的，提示玩家可以找回重试。 */
	public static void tick() {
		for (int i = OUTBOX.expire(nowMs()).size(); i > 0; i--) {
			notSaved(Component.translatable("message.memorypalace.capture_timeout"));
		}
	}

	/** 发送一条随手记。text 不为空白；from = 输入框是从哪条没保存的找回来的（新写的为 null）。 */
	public static void send(CaptureOutbox.Draft from, String text) {
		boolean encrypt = BookSettings.inboxEncryption();
		CaptureOutbox.Draft draft = OUTBOX.begin(from, text, OffsetDateTime.now(), where(), encrypt, nowMs());
		if (draft == null) {
			PasswordScreen.tell("message.memorypalace.capture_already_saved");
			return;
		}
		if (draft.prepared()) {
			transmit(draft); // 重发上次准备好的同一份内容（同一行密文），服务端靠编号和内容认出是同一条
			return;
		}
		if (!encrypt) {
			OUTBOX.prepare(draft, null);
			transmit(draft);
			return;
		}
		int attempt = draft.attempt();
		// 公钥是明文存在钥匙圈里的，不需要密码；读钥匙圈失败时 ClientKeys 会提示，这条留着等找回（不悄悄改发明文）
		ClientKeys.withKeyring(keyring -> {
			if (!current(draft, attempt, CaptureOutbox.State.PREPARING)) {
				return; // 已经超时、断开或者重试过了：迟到的钥匙圈不再发这一次
			}
			byte[] publicKey = boxPublic(keyring);
			if (publicKey == null) {
				OUTBOX.prepare(draft, null);
				transmit(draft);
				hint(keyring.isEmpty() ? "message.memorypalace.inbox_plain_no_keyring" : "message.memorypalace.inbox_plain_no_box");
				return;
			}
			String fingerprint = InboxCipher.fingerprint(publicKey);
			if (BoxPins.check(fingerprint) == BoxPins.Result.MISMATCH) {
				OUTBOX.confirming(draft);
				confirmMismatch(draft, attempt, publicKey);
			} else {
				seal(draft, publicKey);
			}
		}, () -> {
			if (current(draft, attempt, CaptureOutbox.State.PREPARING)) {
				OUTBOX.fail(draft);
				notSaved(Component.translatable("message.memorypalace.capture_keyring_failed"));
			}
		});
	}

	/** 服务端回了某条随手记的保存结果。 */
	public static void onResult(ToolPayloads.CaptureResult result) {
		boolean ok = "OK".equals(result.status());
		if (OUTBOX.onResult(result.requestId(), ok) == null) {
			return;
		}
		if (ok) {
			say(Component.translatable("message.memorypalace.captured", result.detail()));
		} else {
			notSaved(reason(result));
		}
	}

	private static Component reason(ToolPayloads.CaptureResult result) {
		return switch (result.status()) {
			case "ADMIN_ONLY" -> Component.translatable("message.memorypalace.admin_only");
			case "MULTIPLAYER_OFF" -> Component.translatable("message.memorypalace.multiplayer_off");
			case "NO_VAULT" -> Component.translatable("message.memorypalace.no_vault");
			case "BUSY" -> Component.translatable("message.memorypalace.capture_busy");
			case "QUOTA" -> Component.translatable("command.memorypalace.quota_notes", result.detail());
			case "NOT_YOURS" -> Component.translatable("message.memorypalace.inbox_not_yours");
			case "ENCRYPTED" -> Component.translatable("message.memorypalace.inbox_encrypted");
			case "FULL" -> Component.translatable("message.memorypalace.inbox_full");
			case "INVALID" -> Component.translatable("message.memorypalace.capture_invalid");
			default -> Component.translatable("message.memorypalace.capture_failed");
		};
	}

	private static void notSaved(Component reason) {
		say(Component.translatable("message.memorypalace.capture_not_saved", reason,
				PalaceKeys.capture.getTranslatedKeyMessage()));
	}

	private static void say(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}

	private static boolean current(CaptureOutbox.Draft draft, int attempt, CaptureOutbox.State state) {
		return draft.attempt() == attempt && draft.state() == state;
	}

	/** 当前连着的是哪个世界：单人游戏按存档文件夹，服务器按地址。换了世界重试就当作新的一条（重新用那边的公钥加密）。 */
	private static String where() {
		Minecraft mc = Minecraft.getInstance();
		var local = mc.getSingleplayerServer();
		if (local != null) {
			return "local|" + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		}
		ServerData server = mc.getCurrentServer();
		return "remote|" + (server == null || server.ip == null ? "unknown" : server.ip.strip().toLowerCase(Locale.ROOT));
	}

	private static long nowMs() {
		return System.nanoTime() / 1_000_000;
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

	private static void seal(CaptureOutbox.Draft draft, byte[] publicKey) {
		String line;
		try {
			line = InboxCipher.seal(publicKey, Inbox.entryLine(draft.time(), draft.text()));
		} catch (IllegalArgumentException e) {
			// 空内容、内容太长（加密后超过一行的上限）、公钥坏了：不发、也不改发明文，留着等找回
			OUTBOX.fail(draft);
			notSaved(Component.translatable("message.memorypalace.capture_seal_failed"));
			return;
		}
		OUTBOX.prepare(draft, line);
		transmit(draft);
	}

	private static void transmit(CaptureOutbox.Draft draft) {
		boolean retry = OUTBOX.sending(draft, nowMs());
		// 带上第一次按回车时的本地时间和时区："今天"按玩家所在地算（出国、跨日期变更线都对），重发也记进同一天
		String time = draft.time().toString();
		ClientPlayNetworking.send(draft.sealedLine() == null
				? new ToolPayloads.Capture(draft.id(), retry, draft.text(), time)
				: new ToolPayloads.CaptureSealed(draft.id(), retry, draft.sealedLine(), time));
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
	private static void confirmMismatch(CaptureOutbox.Draft draft, int attempt, byte[] publicKey) {
		Minecraft mc = Minecraft.getInstance();
		Runnable backToInput = () -> {
			if (current(draft, attempt, CaptureOutbox.State.CONFIRMING)) {
				OUTBOX.fail(draft);
				mc.gui.setScreen(new QuickCaptureScreen(draft));
			} else {
				mc.gui.setScreen(null);
			}
		};
		mc.gui.setScreen(new ConfirmScreen(proceed -> {
			if (!proceed) {
				backToInput.run();
				return;
			}
			mc.gui.setScreen(new ConfirmScreen(really -> {
				if (really && current(draft, attempt, CaptureOutbox.State.CONFIRMING)) {
					seal(draft, publicKey);
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
