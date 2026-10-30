package com.memorypalace.core.vault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记改名后，把其他笔记里指向它的链接改成新名字（[[旧名]] → [[新名]]，[文字](旧名.md) → [文字](新名.md)）。
 * 只改原来确实解析到这篇的链接；改完再按新名字解析一遍，对不上（比如新名字和别的笔记重名）就不改。
 * 代码块、行内代码里的不动；显示文字（[[名字|显示文字]] 的后半、[文字] 部分）、#标题 部分原样保留。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class LinkRewriter {
	private LinkRewriter() {}

	// 和 MarkdownParser 的 [[…]] 一致：group(1) 是目标（可能带 #标题）
	private static final Pattern WIKI = Pattern.compile("(?<!!)\\[\\[([^\\[\\]|\\n]+)(?:\\|[^\\[\\]\\n]*)?\\]\\]");
	// [文字](目标) / [文字](<目标>) 后面可以跟 "标题"：group(1) 是 <…> 里的，group(2) 是不带尖括号的
	private static final Pattern MARKDOWN = Pattern.compile("\\]\\((?:<([^<>\\n]+)>|([^()\\s<>]+))(?:\\s+\"[^\"\\n]*\")?\\)");
	private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

	/** 一次改名的前后：before / after 是改名前后整个笔记库的解析器，target 是被改名的那篇。 */
	public record Rename(NoteLinks.Resolver before, NoteLinks.Resolver after, UUID target, String oldPath, String newPath) {
		/** 链接所在的笔记改名前后的路径：只有被改名的那篇自己会变。 */
		String sourceAfter(UUID source, String sourceBefore) {
			return source.equals(target) ? newPath : sourceBefore;
		}
	}

	/** 改名后整个笔记库的结果：updated 篇改好了，skipped 篇有指向它的链接但没权限改（或保存时冲突）。 */
	public record Result(int updated, int skipped) {}

	/**
	 * 改名之后调用（vault 已经是新名字）：逐篇改明文笔记里的链接，用 writeBody 保存（会记历史版本）。
	 * before 是改名前那篇的卡片；mayEdit 决定能不能替这个人改（不能改的只计数）。加密笔记服务端读不出来，跳过。
	 */
	public static Result afterRename(Vault vault, NoteSummary before, Predicate<NoteSummary> mayEdit) {
		List<NoteSummary> now = vault.list();
		NoteSummary after = now.stream().filter(n -> n.id().equals(before.id())).findFirst().orElse(null);
		if (after == null || after.relativePath().equals(before.relativePath())) {
			return new Result(0, 0);
		}
		List<NoteSummary> old = new ArrayList<>(now.size());
		for (NoteSummary n : now) {
			old.add(n.id().equals(before.id()) ? before : n);
		}
		Rename rename = new Rename(new NoteLinks.Resolver(old), new NoteLinks.Resolver(now), before.id(),
				before.relativePath(), after.relativePath());
		// 先粗筛：正文里连旧文件名都没有的不可能链接到它（%20 编码的也算上）
		String stem = NoteLinks.stem(before.relativePath()).toLowerCase(Locale.ROOT);
		String encoded = stem.replace(" ", "%20");
		int updated = 0;
		int skipped = 0;
		for (NoteSummary note : now) {
			if (note.encrypted()) {
				continue;
			}
			String body;
			try {
				body = vault.readBody(note.id()).orElse(null);
			} catch (IOException e) {
				continue;
			}
			if (body == null) {
				continue;
			}
			String lower = body.toLowerCase(Locale.ROOT);
			if (!lower.contains(stem) && !lower.contains(encoded)) {
				continue;
			}
			String sourceBefore = note.id().equals(before.id()) ? before.relativePath() : note.relativePath();
			String rewritten = rewrite(body, note.id(), sourceBefore, rename);
			if (rewritten.equals(body)) {
				continue;
			}
			if (!mayEdit.test(note)) {
				skipped++;
				continue;
			}
			try {
				Vault.WriteResult result = vault.writeBody(note.id(), rewritten, Vault.hash(body),
						n -> !n.encrypted() && mayEdit.test(n));
				if (result == Vault.WriteResult.OK) {
					updated++;
				} else {
					skipped++;
				}
			} catch (IOException e) {
				skipped++;
			}
		}
		return new Result(updated, skipped);
	}

	/** 一篇笔记的正文里指向 rename.target 的链接改成新名字；没有要改的原样返回。 */
	public static String rewrite(String body, UUID source, String sourceBefore, Rename rename) {
		String sourceAfter = rename.sourceAfter(source, sourceBefore);
		StringBuilder out = new StringBuilder(body.length() + 16);
		String fence = null;
		int start = 0;
		while (start <= body.length()) {
			int end = body.indexOf('\n', start);
			if (end < 0) {
				end = body.length();
			}
			String line = body.substring(start, end);
			Matcher f = FENCE.matcher(line);
			if (fence != null) {
				// 代码块里原样保留，直到同样的围栏（同一种字符、不短于开头的）
				if (f.find() && f.group(1).charAt(0) == fence.charAt(0) && f.group(1).length() >= fence.length()) {
					fence = null;
				}
				out.append(line);
			} else if (f.find()) {
				fence = f.group(1);
				out.append(line);
			} else {
				out.append(rewriteLine(line, sourceBefore, sourceAfter, rename));
			}
			if (end == body.length()) {
				break;
			}
			out.append('\n');
			start = end + 1;
		}
		return out.toString();
	}

	private record Edit(int start, int end, String text) {}

	private static String rewriteLine(String line, String sourceBefore, String sourceAfter, Rename rename) {
		if (line.indexOf('[') < 0) {
			return line;
		}
		boolean[] code = inlineCode(line);
		List<Edit> edits = new ArrayList<>();
		Matcher wiki = WIKI.matcher(line);
		while (wiki.find()) {
			if (code[wiki.start()]) {
				continue;
			}
			String target = wiki.group(1);
			String renamed = renameWiki(target, sourceBefore, sourceAfter, rename);
			if (renamed != null) {
				edits.add(new Edit(wiki.start(1), wiki.end(1), renamed));
			}
		}
		Matcher md = MARKDOWN.matcher(line);
		while (md.find()) {
			if (code[md.start()]) {
				continue;
			}
			boolean angle = md.group(1) != null;
			int group = angle ? 1 : 2;
			String renamed = renameMarkdown(md.group(group), angle, sourceBefore, sourceAfter, rename);
			if (renamed != null) {
				edits.add(new Edit(md.start(group), md.end(group), renamed));
			}
		}
		if (edits.isEmpty()) {
			return line;
		}
		edits.sort((a, b) -> Integer.compare(a.start(), b.start()));
		StringBuilder out = new StringBuilder(line.length() + 16);
		int at = 0;
		for (Edit edit : edits) {
			if (edit.start() < at) {
				continue; // 两种写法重叠（不该出现）：只改前一个
			}
			out.append(line, at, edit.start()).append(edit.text());
			at = edit.end();
		}
		return out.append(line.substring(at)).toString();
	}

	/** [[目标]] 里的目标：原来指向被改名的那篇就换成新名字（保留文件夹前缀、.md 写法和 #标题），否则 null。 */
	private static String renameWiki(String target, String sourceBefore, String sourceAfter, Rename rename) {
		if (!points(rename.before(), sourceBefore, NoteLinks.WIKI + target, rename.target())) {
			return null;
		}
		int hash = target.indexOf('#');
		String name = (hash < 0 ? target : target.substring(0, hash)).strip();
		String anchor = hash < 0 ? "" : target.substring(hash);
		String ext = name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(name.length() - 3) : "";
		String newStem = NoteLinks.stem(rename.newPath());
		int slash = name.lastIndexOf('/');
		List<String> candidates = List.of(
				(slash < 0 ? "" : name.substring(0, slash + 1)) + newStem + ext,
				NoteLinks.withoutMd(rename.newPath()) + ext);
		for (String candidate : candidates) {
			if (candidate.matches(".*[\\[\\]|#\\n].*")) {
				continue;
			}
			if (points(rename.after(), sourceAfter, NoteLinks.WIKI + candidate, rename.target())) {
				return candidate + anchor;
			}
		}
		return null;
	}

	/** [文字](目标) 里的目标：只换最后一段文件名，相对路径、编码方式、.md 写法、#标题 都照旧；否则 null。 */
	private static String renameMarkdown(String target, boolean angle, String sourceBefore, String sourceAfter, Rename rename) {
		if (NoteLinks.isExternal(target) || !points(rename.before(), sourceBefore, target, rename.target())) {
			return null;
		}
		int hash = target.indexOf('#');
		String path = hash < 0 ? target : target.substring(0, hash);
		String anchor = hash < 0 ? "" : target.substring(hash);
		int slash = path.lastIndexOf('/');
		String prefix = path.substring(0, slash + 1);
		String last = NoteLinks.decode(path.substring(slash + 1));
		String ext = last.toLowerCase(Locale.ROOT).endsWith(".md") ? last.substring(last.length() - 3) : "";
		String name = NoteLinks.stem(rename.newPath()) + ext;
		// 尖括号里可以直接写空格；不带尖括号的写成 %20（原来就用了百分号编码的，连同 % ( ) 一起编码）
		boolean encoded = !angle && (path.indexOf('%') >= 0 || name.indexOf(' ') >= 0 || name.indexOf('(') >= 0 || name.indexOf(')') >= 0);
		if (encoded) {
			name = name.replace("%", "%25").replace(" ", "%20").replace("(", "%28").replace(")", "%29").replace("#", "%23");
		} else if (name.indexOf('#') >= 0 || name.indexOf('>') >= 0 || name.indexOf('<') >= 0) {
			return null;
		}
		String candidate = prefix + name + anchor;
		return points(rename.after(), sourceAfter, candidate, rename.target()) ? candidate : null;
	}

	private static boolean points(NoteLinks.Resolver resolver, String source, String link, UUID target) {
		return resolver.resolve(source, link).filter(n -> n.id().equals(target)).isPresent();
	}

	/** 这一行里每个字符在不在行内代码（`…`）里。反引号个数要对上，对不上的反引号当普通字符。 */
	private static boolean[] inlineCode(String line) {
		boolean[] code = new boolean[line.length() + 1];
		int i = 0;
		while (i < line.length()) {
			if (line.charAt(i) != '`') {
				i++;
				continue;
			}
			int run = i;
			while (run < line.length() && line.charAt(run) == '`') {
				run++;
			}
			int ticks = run - i;
			int close = -1;
			for (int j = run; j < line.length(); ) {
				if (line.charAt(j) != '`') {
					j++;
					continue;
				}
				int k = j;
				while (k < line.length() && line.charAt(k) == '`') {
					k++;
				}
				if (k - j == ticks) {
					close = k;
					break;
				}
				j = k;
			}
			if (close < 0) {
				i = run;
				continue;
			}
			for (int j = i; j < close; j++) {
				code[j] = true;
			}
			i = close;
		}
		return code;
	}
}
