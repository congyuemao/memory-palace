package com.memorypalace.mc;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端让客户端做的加密相关动作（CryptoPayloads.Action 的 action 字段）。网络上传 wire 字符串。
 * noteId / title / body 的含义按动作不同，见各项注释。
 */
public enum CryptoAction {
	/** 加密这篇（/palace encrypt、待上锁的书）：body = 现在的明文。 */
	ENCRYPT("encrypt"),
	/** 加密刚新建的这篇，然后打开编辑器（/palace newsecret、空白上锁笔记）：body = 明文。 */
	ENCRYPT_NEW("encrypt_new"),
	/** 刚在被锁的文件夹里新建了这篇：用文件夹钥匙加密后打开编辑器。 */
	ENCRYPT_NEW_FOLDER("encrypt_new_folder"),
	/** 解除加密：body = 现在的密文（只认玩家刚在这篇上点过"解密"的）。 */
	DECRYPT("decrypt"),
	/** 立刻上锁（noteId 无意义）。 */
	LOCK("lock"),
	/** 给文件夹设密码（/palace lockfolder）：title = 文件夹。 */
	LOCK_FOLDER("lock_folder"),
	/** 文件夹已经有锁、上次没加密完：title = 文件夹，body = 锁的内容。 */
	RESUME_FOLDER("resume_folder"),
	/** 整个文件夹加密时的一篇：body = 现在的正文。 */
	ENCRYPT_FOLDER("encrypt_folder"),
	/** 整个文件夹加密的篇都发完了：title = 文件夹，body = 发了几篇。 */
	ENCRYPT_FOLDER_DONE("encrypt_folder_done"),
	/** 解除文件夹密码时的一篇：body = 密文。 */
	DECRYPT_FOLDER("decrypt_folder"),
	/** 解除文件夹密码的篇都发完了：title = 文件夹，body = 发了几篇。 */
	DECRYPT_FOLDER_DONE("decrypt_folder_done"),
	/** 修改文件夹密码：title = 文件夹，body = 锁的内容。 */
	FOLDER_PASSWORD("folder_password"),
	/** 解除文件夹密码：title = 文件夹，body = 锁的内容。 */
	UNLOCK_FOLDER("unlock_folder"),
	/** 给这篇设阅读密码：body = 密文。 */
	SET_READ_PASSWORD("set_read_password"),
	/** 取消这篇的阅读密码：body = 密文。 */
	REMOVE_READ_PASSWORD("remove_read_password"),
	/** 只读查看一个历史版本：body = 那个版本。 */
	VIEW_VERSION("view_version"),
	/** 还原一个历史版本：body = "现在的正文长度:现在的正文旧版本"。 */
	RESTORE_VERSION("restore_version"),
	/** 抄写加密笔记：noteId = 新笔记，body = "原笔记ID:原密文"。 */
	COPY_ENCRYPTED("copy_encrypted"),
	/** 抄进被锁的文件夹：noteId = 目标，body = FolderCopy.pack(...)。 */
	COPY_INTO_FOLDER("copy_into_folder");

	public final String wire;

	CryptoAction(String wire) {
		this.wire = wire;
	}

	private static final Map<String, CryptoAction> BY_WIRE = new HashMap<>();

	static {
		for (CryptoAction action : values()) {
			BY_WIRE.put(action.wire, action);
		}
	}

	/** 网络上收到的字符串对应的动作；认不出时为 null。 */
	public static CryptoAction fromWire(String wire) {
		return BY_WIRE.get(wire);
	}
}
