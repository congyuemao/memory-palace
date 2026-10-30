package com.memorypalace.core.vault;

import com.memorypalace.core.markdown.MarkdownParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 笔记之间的链接：把链接（见 MarkdownParser 的 MdSpan.link）对应到笔记库里的一篇笔记。
 * <ul>
 * <li>Obsidian 写法 [[笔记名]]、[[文件夹/笔记名|显示文字]]、[[笔记名#标题]]：link 是 "wiki:笔记名"。
 *   不带"/"时按文件名找（不分大小写，可以不写 .md）；带"/"时按路径找，路径不完整时按结尾匹配。
 *   有好几篇同名时，优先和当前笔记在同一个文件夹的，其次路径最短的，再按路径排序</li>
 * <li>Markdown 写法 [文字](文件夹/笔记.md)：相对当前笔记所在的文件夹，以"/"开头的相对笔记库根目录；可以不写 .md，
 *   支持 %20 这样的编码。按路径找不到时，再按 Obsidian 的方式按文件名找一次</li>
 * <li>"#标题"部分只用来定位笔记，不跳到标题；带协议的（http:、mailto: 等）是外部链接，不对应笔记</li>
 * </ul>
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteLinks {
	public static final String WIKI = "wiki:";
	private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]+:.*", Pattern.DOTALL);

	private NoteLinks() {}

	/** 外部链接（http:、https:、mailto: 等带协议的地址）。 */
	public static boolean isExternal(String link) {
		return link != null && !link.startsWith(WIKI) && SCHEME.matcher(link).matches();
	}

	/** 链接对应 notes 里的哪一篇；sourcePath 是链接所在笔记的相对路径。对应不上（或是外部链接）时为 empty。 */
	public static Optional<NoteSummary> resolve(Collection<NoteSummary> notes, String sourcePath, String link) {
		return new Resolver(notes).resolve(sourcePath, link);
	}

	/**
	 * 在一组笔记里反复解析链接时用：按小写路径、小写文件名建好索引，每个链接不用再把全部笔记过一遍。
	 */
	public static final class Resolver {
		private final java.util.Map<String, NoteSummary> byPath = new java.util.HashMap<>();
		private final java.util.Map<String, List<NoteSummary>> byStem = new java.util.HashMap<>();

		public Resolver(Collection<NoteSummary> notes) {
			for (NoteSummary note : notes) {
				String lower = note.relativePath().toLowerCase(Locale.ROOT);
				byPath.putIfAbsent(lower, note);
				byStem.computeIfAbsent(stem(lower), k -> new ArrayList<>()).add(note);
			}
		}

		public Optional<NoteSummary> resolve(String sourcePath, String link) {
			if (link == null || link.isBlank() || isExternal(link)) {
				return Optional.empty();
			}
			String folder = folderOf(sourcePath);
			if (link.startsWith(WIKI)) {
				return byName(folder, withoutAnchor(link.substring(WIKI.length())));
			}
			String target = withoutAnchor(decode(link));
			if (target.isEmpty()) {
				return Optional.empty();
			}
			String path = target.startsWith("/") ? normalize(target.substring(1)) : normalize(folder.isEmpty() ? target : folder + "/" + target);
			if (path != null) {
				Optional<NoteSummary> exact = exact(path);
				if (exact.isPresent()) {
					return exact;
				}
			}
			return byName(folder, target);
		}

		private Optional<NoteSummary> exact(String path) {
			String lower = path.toLowerCase(Locale.ROOT);
			NoteSummary hit = byPath.get(lower);
			return Optional.ofNullable(hit != null ? hit : byPath.get(lower + ".md"));
		}

		private Optional<NoteSummary> byName(String folder, String name) {
			String target = name.strip().replace('\\', '/');
			while (target.startsWith("/")) {
				target = target.substring(1);
			}
			if (target.isEmpty()) {
				return Optional.empty();
			}
			String wanted = target;
			List<NoteSummary> candidates;
			if (wanted.contains("/")) {
				candidates = byStem.getOrDefault(stem(wanted.toLowerCase(Locale.ROOT)), List.of()).stream()
						.filter(n -> samePath(n.relativePath(), wanted) || endsWithPath(n.relativePath(), wanted)).toList();
			} else {
				candidates = byStem.getOrDefault(stem(wanted.toLowerCase(Locale.ROOT)), List.of());
			}
			return candidates.stream()
					.min(Comparator.comparing((NoteSummary n) -> !folderOf(n.relativePath()).equals(folder))
							.thenComparingInt(n -> n.relativePath().length())
							.thenComparing(NoteSummary::relativePath));
		}
	}

	// 正文 → 里面的链接：Vault 对没变的笔记返回同一个正文对象，按对象相同判断还能不能用
	private record Parsed(String body, List<String> links) {}

	private static final java.util.Map<java.util.UUID, Parsed> LINKS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final int MAX_PARSED = 20_000;

	private static List<String> linksOf(java.util.UUID id, String body) {
		Parsed cached = LINKS.get(id);
		if (cached != null && cached.body() == body) {
			return cached.links();
		}
		List<String> links = MarkdownParser.links(body);
		if (LINKS.size() > MAX_PARSED) {
			LINKS.clear();
		}
		LINKS.put(id, new Parsed(body, links));
		return links;
	}

	/**
	 * 哪些笔记链接到了 target（不含它自己）。只看 visible 里的明文笔记：加密笔记的正文服务端读不出来。按路径排序。
	 */
	public static List<NoteSummary> backlinks(Vault vault, NoteSummary target, Predicate<NoteSummary> visible) {
		List<NoteSummary> all = vault.list();
		Resolver resolver = new Resolver(all);
		List<NoteSummary> out = new ArrayList<>();
		for (NoteSummary note : all) {
			if (note.id().equals(target.id()) || note.encrypted() || !visible.test(note)) {
				continue;
			}
			String body;
			try {
				body = vault.readBody(note.id()).orElse("");
			} catch (java.io.IOException e) {
				continue;
			}
			for (String link : linksOf(note.id(), body)) {
				if (resolver.resolve(note.relativePath(), link).filter(n -> n.id().equals(target.id())).isPresent()) {
					out.add(note);
					break;
				}
			}
		}
		out.sort(Comparator.comparing(NoteSummary::relativePath));
		return out;
	}

	// ---- 内部 ----

	private static boolean samePath(String relativePath, String path) {
		return relativePath.equalsIgnoreCase(path) || relativePath.equalsIgnoreCase(path + ".md");
	}

	private static boolean endsWithPath(String relativePath, String path) {
		String lower = relativePath.toLowerCase(Locale.ROOT);
		String p = path.toLowerCase(Locale.ROOT);
		return lower.endsWith("/" + p) || lower.endsWith("/" + p + ".md");
	}

	/** 文件名去掉 .md。 */
	private static String stem(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		return name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(0, name.length() - 3) : name;
	}

	private static String folderOf(String path) {
		if (path == null) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	private static String withoutAnchor(String target) {
		int hash = target.indexOf('#');
		return (hash < 0 ? target : target.substring(0, hash)).strip();
	}

	/** 去掉 "."、处理 ".."；跑出笔记库根目录时为 null。 */
	private static String normalize(String path) {
		Deque<String> parts = new ArrayDeque<>();
		for (String part : path.replace('\\', '/').split("/")) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.equals("..")) {
				if (parts.isEmpty()) {
					return null;
				}
				parts.removeLast();
			} else {
				parts.addLast(part);
			}
		}
		return parts.isEmpty() ? null : String.join("/", parts);
	}

	/** %20 这样的百分号编码按 UTF-8 解开（"+" 保持原样，它在文件名里就是加号）；编码不对时原样返回。 */
	private static String decode(String text) {
		if (text.indexOf('%') < 0) {
			return text;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < text.length(); ) {
			if (text.charAt(i) == '%') {
				if (i + 2 >= text.length()) {
					return text;
				}
				int high = Character.digit(text.charAt(i + 1), 16);
				int low = Character.digit(text.charAt(i + 2), 16);
				if (high < 0 || low < 0) {
					return text;
				}
				out.write(high * 16 + low);
				i += 3;
				continue;
			}
			int cp = text.codePointAt(i);
			byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
			out.write(bytes, 0, bytes.length);
			i += Character.charCount(cp);
		}
		return out.toString(StandardCharsets.UTF_8);
	}
}
