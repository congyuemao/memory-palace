package com.memorypalace.core.vault;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 谁能读、谁能改一篇笔记。这里只有规则：不读文件，也不认识 Minecraft 的玩家——调用方说清楚"这个人是谁、是不是管理员"。
 * <p>
 * front matter 里的字段（都可以省略）：
 * <pre>
 * palace-owner: &lt;玩家 UUID&gt;                   作者。省略 = 无主笔记（单人时代的笔记都是），归管理员
 * palace-read:  everyone | &lt;UUID&gt;, &lt;UUID&gt; …     谁能读。省略时看世界设置"笔记可见性"
 * palace-edit:  everyone | &lt;UUID&gt;, &lt;UUID&gt; …     作者之外谁能改（能改的人也能读）。省略时只有作者
 * </pre>
 * 规则（"作者"对无主笔记来说就是管理员）：
 * - 作者能读能改
 * - 收件箱和加密笔记只有作者能碰，不看 palace-read / palace-edit，也不受可见性影响
 *   （收件箱永远私有；加密笔记的钥匙只在作者手里，分享以后靠单篇密码）
 * - 其他笔记：palace-edit 里的人能改能读；写了 palace-read 就按它；没写就看可见性
 * - 管理员没有额外特权：他本来就能直接读硬盘，但在游戏里也按规则来
 * <p>
 * 用 UUID 而不是玩家名：名字可以改，UUID 不变。写错的 UUID 直接忽略。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteAccess {
	public static final String OWNER_KEY = "palace-owner";
	public static final String READ_KEY = "palace-read";
	public static final String EDIT_KEY = "palace-edit";
	private static final String EVERYONE = "everyone";

	private NoteAccess() {}

	/** 世界设置"笔记可见性"：没写 palace-read 的笔记谁能读。 */
	public enum Visibility {
		/** 世界里的人都能读（默认）：不加锁 = 公开，加锁 = 私密。 */
		EVERYONE,
		/** 只有作者能读，要给别人看就写 palace-read。适合人杂的服务器。 */
		PRIVATE;

		/** "everyone" / "private"，不区分大小写；认不出来时用默认值 EVERYONE。 */
		public static Visibility parse(String value) {
			return value != null && value.strip().toLowerCase(Locale.ROOT).equals("private") ? PRIVATE : EVERYONE;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** 正在访问的人。admin = 本机房主或者服务器管理员（OP）。 */
	public record Who(UUID player, boolean admin) {}

	/**
	 * 一篇笔记的权限设置。
	 * @param owner   作者；null = 无主笔记
	 * @param read    palace-read 写的名单；null = 没写（看可见性）
	 * @param edit    palace-edit 写的名单；空 = 只有作者
	 * 名单里的 EVERYONE_ID 表示 everyone。
	 */
	public record Rules(UUID owner, Set<UUID> read, Set<UUID> edit) {
		/** 名单里代表 everyone 的特殊值（全零 UUID，不会是真实玩家）。 */
		public static final UUID EVERYONE_ID = new UUID(0, 0);
		/** 什么都没写：无主、读看可见性、只有管理员能改。 */
		public static final Rules NONE = new Rules(null, null, Set.of());

		public Rules {
			read = read == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(read));
			edit = Collections.unmodifiableSet(new LinkedHashSet<>(edit));
		}
	}

	/** 从 front matter 字段读出权限设置。 */
	public static Rules parse(Map<String, String> fields) {
		UUID owner = parseUuid(fields.get(OWNER_KEY));
		if (Rules.EVERYONE_ID.equals(owner)) {
			owner = null; // 全零 UUID 不是真实玩家，当成没写
		}
		Set<UUID> read = fields.containsKey(READ_KEY) ? parseList(fields.get(READ_KEY)) : null;
		Set<UUID> edit = fields.containsKey(EDIT_KEY) ? parseList(fields.get(EDIT_KEY)) : Set.of();
		return new Rules(owner, read, edit);
	}

	/** 是不是作者（无主笔记的作者是管理员）。 */
	public static boolean isOwner(Rules rules, Who who) {
		return rules.owner() == null ? who.admin() : rules.owner().equals(who.player());
	}

	/**
	 * @param ownerOnly 收件箱或加密笔记：只有作者能碰
	 */
	public static boolean canRead(Rules rules, boolean ownerOnly, Who who, Visibility visibility) {
		if (isOwner(rules, who)) {
			return true;
		}
		if (ownerOnly) {
			return false;
		}
		if (listed(rules.edit(), who)) {
			return true;
		}
		return rules.read() != null ? listed(rules.read(), who) : visibility == Visibility.EVERYONE;
	}

	public static boolean canEdit(Rules rules, boolean ownerOnly, Who who) {
		if (isOwner(rules, who)) {
			return true;
		}
		return !ownerOnly && listed(rules.edit(), who);
	}

	/** 笔记书上、准星提示里能不能用正文标题：世界里每个人都能读的笔记才可以，否则标题本身就是泄露。 */
	public static boolean titleIsPublic(Rules rules, boolean ownerOnly, Visibility visibility) {
		if (ownerOnly) {
			return false;
		}
		Set<UUID> readers = rules.read();
		boolean everyone = readers != null ? readers.contains(Rules.EVERYONE_ID) : visibility == Visibility.EVERYONE;
		return everyone || rules.edit().contains(Rules.EVERYONE_ID);
	}

	private static boolean listed(Set<UUID> list, Who who) {
		return list.contains(Rules.EVERYONE_ID) || list.contains(who.player());
	}

	/** "everyone" 或逗号分隔的 UUID（两边可以有方括号，兼容 YAML 的 [a, b] 写法）；认不出的项丢掉。 */
	private static Set<UUID> parseList(String value) {
		Set<UUID> out = new LinkedHashSet<>();
		if (value == null) {
			return out;
		}
		String v = value.strip();
		if (v.startsWith("[") && v.endsWith("]")) {
			v = v.substring(1, v.length() - 1);
		}
		for (String item : v.split(",")) {
			String s = item.strip();
			if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
				s = s.substring(1, s.length() - 1).strip();
			}
			if (s.equalsIgnoreCase(EVERYONE)) {
				out.add(Rules.EVERYONE_ID);
			} else {
				UUID id = parseUuid(s);
				if (id != null && !id.equals(Rules.EVERYONE_ID)) {
					out.add(id);
				}
			}
		}
		return out;
	}

	private static UUID parseUuid(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			UUID id = UUID.fromString(value.strip());
			// UUID.fromString 很宽松（"1-1-1-1-1" 也收），只认标准的 36 位写法
			return id.toString().equalsIgnoreCase(value.strip()) ? id : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
