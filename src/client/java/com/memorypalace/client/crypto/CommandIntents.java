package com.memorypalace.client.crypto;

import java.util.EnumMap;
import java.util.Map;

/**
 * "玩家刚在这台电脑上亲手输入过这条命令"的记录。服务端发来的某些请求（打开编辑器、加密、设文件夹密码……）
 * 只在玩家 WINDOW 之内亲手输入过对应命令时才认，免得恶意服务端随时弹框、骗玩家输密码或把秘密存成明文。
 * 同一组里的命令共用一个记录：后输入的覆盖先输入的，收到这一组的任何请求都会用掉它。
 */
final class CommandIntents {
	/** 记录有效多久。 */
	static final long WINDOW_NANOS = 10_000_000_000L;

	enum Kind {
		NEW_NOTE("palace new ", Group.NEW_NOTE),
		ENCRYPT("palace encrypt ", Group.ENCRYPT),
		NEW_SECRET("palace newsecret ", Group.ENCRYPT),
		LOCK_FOLDER("palace lockfolder ", Group.LOCK_FOLDER),
		FOLDER_PASSWORD("palace folderpassword ", Group.FOLDER_MANAGE),
		UNLOCK_FOLDER("palace unlockfolder ", Group.FOLDER_MANAGE),
		SET_READ_PASSWORD("palace sharepassword ", Group.READ_PASSWORD),
		REMOVE_READ_PASSWORD("palace unsharepassword ", Group.READ_PASSWORD);

		final String prefix;
		final Group group;

		Kind(String prefix, Group group) {
			this.prefix = prefix;
			this.group = group;
		}
	}

	enum Group { NEW_NOTE, ENCRYPT, LOCK_FOLDER, FOLDER_MANAGE, READ_PASSWORD }

	private record Mark(Kind kind, long atNanos) {}

	private static final Map<Group, Mark> MARKS = new EnumMap<>(Group.class);

	private CommandIntents() {}

	/** 玩家发出了一条命令（不带开头的 "/"）：是这几种之一就记下来。 */
	static void onCommand(String command) {
		String c = command.strip();
		for (Kind kind : Kind.values()) {
			if (c.startsWith(kind.prefix)) {
				MARKS.put(kind.group, new Mark(kind, System.nanoTime()));
				return;
			}
		}
	}

	/** 这一组的记录是不是 kinds 之一、还在有效期内；不管是不是，这一组的记录都用掉。 */
	static boolean take(Group group, Kind... kinds) {
		Mark mark = MARKS.remove(group);
		if (mark == null || System.nanoTime() - mark.atNanos() > WINDOW_NANOS) {
			return false;
		}
		for (Kind kind : kinds) {
			if (mark.kind() == kind) {
				return true;
			}
		}
		return false;
	}

	/** 离开世界：之前的命令都不算了。 */
	static void clear() {
		MARKS.clear();
	}
}
