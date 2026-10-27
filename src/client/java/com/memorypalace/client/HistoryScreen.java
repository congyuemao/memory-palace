package com.memorypalace.client;

import com.memorypalace.core.vault.Vault;
import net.minecraft.network.chat.Component;

/**
 * 只读查看一份历史版本（/palace history 里点"查看"）。没有编辑、解密按钮，也不跟着笔记热重载。
 * secret = 这是解开的加密笔记：上锁时和其他加密笔记的界面一起关掉（见 ClientKeys.lock）。
 */
public class HistoryScreen extends NoteScreen {
	private final boolean secret;

	public HistoryScreen(Component title, String body, boolean secret) {
		super(null, title, body, false, Vault.hash(body), false);
		this.secret = secret;
	}

	public boolean secret() {
		return secret;
	}
}
