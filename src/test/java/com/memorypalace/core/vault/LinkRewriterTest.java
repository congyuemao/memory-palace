package com.memorypalace.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LinkRewriterTest {
	private static NoteSummary note(String path) {
		return new NoteSummary(UUID.nameUUIDFromBytes(path.getBytes()), path, path, false, NoteAccess.Rules.NONE);
	}

	/** 把 notes 里 oldPath 那篇改名成 newPath，得到 LinkRewriter.Rename。 */
	private static LinkRewriter.Rename rename(List<NoteSummary> notes, String oldPath, String newPath) {
		NoteSummary target = notes.stream().filter(n -> n.relativePath().equals(oldPath)).findFirst().orElseThrow();
		List<NoteSummary> after = notes.stream()
				.map(n -> n == target ? new NoteSummary(n.id(), newPath, n.title(), false, n.access()) : n).toList();
		return new LinkRewriter.Rename(new NoteLinks.Resolver(notes), new NoteLinks.Resolver(after), target.id(), oldPath, newPath);
	}

	private static final List<NoteSummary> NOTES = List.of(
			note("欢迎.md"), note("线代/特征值.md"), note("线代/矩阵.md"), note("数学/矩阵.md"), note("a b/空 格.md"));

	private static String rewrite(String body, String source, String oldPath, String newPath) {
		return LinkRewriter.rewrite(body, UUID.randomUUID(), source, rename(NOTES, oldPath, newPath));
	}

	@Test
	void wikiLinksFollowTheRename() {
		assertEquals("见 [[本征值]]、[[本征值|别名]]、[[本征值#定义]]、[[线代/本征值]]、[[本征值.md]]",
				rewrite("见 [[特征值]]、[[特征值|别名]]、[[特征值#定义]]、[[线代/特征值]]、[[特征值.md]]",
						"欢迎.md", "线代/特征值.md", "线代/本征值.md"));
	}

	@Test
	void onlyLinksThatPointedToTheRenamedNoteChange() {
		// 在"数学"文件夹里 [[矩阵]] 指的是 数学/矩阵，改的是 线代/矩阵：不动
		assertEquals("[[矩阵]]", rewrite("[[矩阵]]", "数学/笔记.md", "线代/矩阵.md", "线代/行列式.md"));
		assertEquals("[[行列式]]", rewrite("[[矩阵]]", "线代/特征值.md", "线代/矩阵.md", "线代/行列式.md"));
	}

	@Test
	void newNameThatWouldBeAmbiguousUsesTheFullPath() {
		// 改名成"特征值"后，从根目录写 [[特征值]] 会先指到路径更短的 线代/特征值：改用完整路径
		assertEquals("[[a b/特征值]]", rewrite("[[空 格]]", "欢迎.md", "a b/空 格.md", "a b/特征值.md"));
		assertEquals("[[矩阵]]", rewrite("[[欢迎]]", "其他/x.md", "欢迎.md", "矩阵.md"));
		// 线代/ 里的 [[矩阵]] 指的是 线代/矩阵，[[矩阵]] 这个写法表达不了根目录那篇：保持原样
		assertEquals("[[欢迎]]", rewrite("[[欢迎]]", "线代/x.md", "欢迎.md", "矩阵.md"));
	}

	@Test
	void markdownLinksKeepTheirStyle() {
		assertEquals("[x](本征值.md) [y](../线代/本征值.md#定义) [z](<本征值.md>)",
				rewrite("[x](特征值.md) [y](../线代/特征值.md#定义) [z](<特征值.md>)", "线代/a.md", "线代/特征值.md", "线代/本征值.md"));
		assertEquals("[x](a%20b/新%20名.md)",
				rewrite("[x](a%20b/%E7%A9%BA%20%E6%A0%BC.md)", "欢迎.md", "a b/空 格.md", "a b/新 名.md"));
		assertEquals("[x](../线代/本%20征.md) [y](<../线代/本 征.md>)",
				rewrite("[x](../线代/特征值.md) [y](<../线代/特征值.md>)", "数学/x.md", "线代/特征值.md", "线代/本 征.md"),
				"新名字带空格：不带尖括号的写成 %20");
	}

	@Test
	void codeIsLeftAlone() {
		String body = "`[[特征值]]` [[特征值]]\n```\n[[特征值]]\n```\n[[特征值]]";
		assertEquals("`[[特征值]]` [[本征值]]\n```\n[[特征值]]\n```\n[[本征值]]",
				rewrite(body, "欢迎.md", "线代/特征值.md", "线代/本征值.md"));
	}

	@Test
	void linksInsideTheRenamedNoteItselfUseItsNewFolder() {
		LinkRewriter.Rename r = rename(NOTES, "线代/特征值.md", "线代/本征值.md");
		assertEquals("[[本征值]] [[矩阵]]", LinkRewriter.rewrite("[[特征值]] [[矩阵]]", r.target(), "线代/特征值.md", r));
	}

	@Test
	void afterRenameUpdatesEditableNotesAndCountsTheRest(@TempDir Path dir) throws Exception {
		Vault vault = new Vault(dir);
		NoteSummary target = vault.createNote("线代/特征值.md", "# 特征值\n");
		NoteSummary mine = vault.createNote("我的.md", "# 我的\n\n见 [[特征值]]。\n");
		NoteSummary theirs = vault.createNote("别人的.md", "# 别人的\n\n见 [特征值](线代/特征值.md)。\n");
		NoteSummary unrelated = vault.createNote("无关.md", "# 无关\n\n[[矩阵]]\n");
		vault.renameNote(target.id(), "本征值");

		LinkRewriter.Result result = LinkRewriter.afterRename(vault, target, n -> !n.id().equals(theirs.id()));
		assertEquals(new LinkRewriter.Result(1, 1), result);
		assertEquals(Optional.of("# 我的\n\n见 [[本征值]]。\n"), vault.readBody(mine.id()));
		assertEquals(Optional.of("# 别人的\n\n见 [特征值](线代/特征值.md)。\n"), vault.readBody(theirs.id()));
		assertEquals(Optional.of("# 无关\n\n[[矩阵]]\n"), vault.readBody(unrelated.id()));
	}

	@Test
	void suggestionsRankPrefixMatchesFirstAndDisambiguateDuplicates() {
		List<NoteLinks.Suggestion> s = NoteLinks.suggest(NOTES, "矩", 8);
		assertEquals(List.of("数学/矩阵", "线代/矩阵"), s.stream().map(NoteLinks.Suggestion::insert).toList(),
				"两篇都叫\"矩阵\"：填完整路径");
		assertEquals(List.of("特征值"), NoteLinks.suggest(NOTES, "征", 8).stream().map(NoteLinks.Suggestion::insert).toList());
		assertEquals(List.of("特征值"), NoteLinks.suggest(NOTES, "线代/特", 8).stream().map(NoteLinks.Suggestion::insert).toList(),
				"按路径也能找到");
		assertEquals(2, NoteLinks.suggest(NOTES, "", 2).size());
	}

	@Test
	void coarseFilterNeverSkipsPercentEncodedNames() {
		String stem = "空 格";
		assertTrue(LinkRewriter.mayLinkTo("[x](a%20b/%E7%A9%BA%20%E6%A0%BC.md)", stem), "大写十六进制");
		assertTrue(LinkRewriter.mayLinkTo("[x](a%20b/%e7%a9%ba%20%e6%a0%bc.md)", stem), "小写十六进制");
		assertTrue(LinkRewriter.mayLinkTo("[x](a%20b/空%20格.md)", stem), "只编码空格");
		assertTrue(LinkRewriter.mayLinkTo("[[空 格]]", stem));
		assertFalse(LinkRewriter.mayLinkTo("[[别的]] 没有百分号", stem));
	}

	// 真实临时笔记库：改名 → afterRename → 重新读正文，改后的链接还要指向同一篇（同一个 UUID）
	private static final String UPPER = "a%20b/%E7%A9%BA%20%E6%A0%BC.md";
	private static final String LOWER = "a%20b/%e7%a9%ba%20%e6%a0%bc.md";

	@Test
	void renameRewritesPercentEncodedLinksEndToEnd(@TempDir Path dir) throws Exception {
		Vault vault = new Vault(dir);
		NoteSummary target = vault.createNote("a b/空 格.md", "# 空 格\n");
		NoteSummary other = vault.createNote("a b/别.md", "# 别\n");
		String body = "# 引用\n\n"
				+ "大写 [甲](" + UPPER + "#定义)、小写 [乙](" + LOWER + ")、尖括号 [丙](<a b/空 格.md>)、[[空 格|别名]]。\n"
				+ "对照 [丁](a%20b/%E5%88%AB.md) 指向别的笔记。\n"
				+ "行内代码 `[戊](" + UPPER + ")` 不动。\n"
				+ "```\n[己](" + UPPER + ")\n```\n";
		NoteSummary source = vault.createNote("引用.md", body);
		// 正文里只有小写编码、没有直写文件名的一篇：旧的粗筛会把它跳过
		NoteSummary encodedOnly = vault.createNote("只有编码.md", "# 只有编码\n\n[乙](" + LOWER + ")\n");
		NoteSummary theirs = vault.createNote("别人的.md", "# 别人的\n\n[甲](" + UPPER + ")\n");
		NoteSummary conflicted = vault.createNote("冲突.md", "# 冲突\n\n[甲](" + UPPER + ")\n");
		NoteSummary sealed = vault.createNote("密文.md", "");
		String cipher = com.memorypalace.core.crypto.NoteCipher.PREFIX + "[甲](" + UPPER + ")";
		assertEquals(Vault.WriteResult.OK, vault.writeBody(sealed.id(), cipher, null));
		NoteSummary before = vault.find(target.id()).orElseThrow();

		vault.renameNote(target.id(), "新 名");
		String externalEdit = "# 冲突\n\n外部刚改过：[甲](" + UPPER + ")\n";
		java.util.concurrent.atomic.AtomicBoolean edited = new java.util.concurrent.atomic.AtomicBoolean();
		LinkRewriter.Result result = LinkRewriter.afterRename(vault, before, n -> {
			if (n.id().equals(conflicted.id()) && edited.compareAndSet(false, true)) {
				// 判断完权限、真正保存之前，这篇在外部被改了：保存要报冲突，不能覆盖外部的修改
				try {
					vault.writeBody(n.id(), externalEdit, null);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}
			return !n.id().equals(theirs.id());
		});

		assertEquals(new LinkRewriter.Result(2, 2), result, "引用、只有编码 改好；别人的（无权）和冲突的各算一篇没改");
		assertEquals(Optional.of("# 只有编码\n\n[乙](a%20b/新%20名.md)\n"), vault.readBody(encodedOnly.id()));
		String expected = "# 引用\n\n"
				+ "大写 [甲](a%20b/新%20名.md#定义)、小写 [乙](a%20b/新%20名.md)、尖括号 [丙](<a b/新 名.md>)、[[新 名|别名]]。\n"
				+ "对照 [丁](a%20b/%E5%88%AB.md) 指向别的笔记。\n"
				+ "行内代码 `[戊](" + UPPER + ")` 不动。\n"
				+ "```\n[己](" + UPPER + ")\n```\n";
		assertEquals(Optional.of(expected), vault.readBody(source.id()));
		assertEquals(Optional.of("# 别人的\n\n[甲](" + UPPER + ")\n"), vault.readBody(theirs.id()));
		assertEquals(Optional.of(externalEdit), vault.readBody(conflicted.id()));
		assertEquals(Optional.of(cipher), vault.readBody(sealed.id()), "密文不动");

		// 改后的每条链接重新解析：对照那条还指向"别"，其余都指向原来那篇（UUID 不变）
		List<NoteSummary> notes = vault.list();
		List<UUID> resolved = com.memorypalace.core.markdown.MarkdownParser.links(expected).stream()
				.map(link -> NoteLinks.resolve(notes, source.relativePath(), link).map(NoteSummary::id).orElse(null))
				.toList();
		assertFalse(resolved.contains(null), "改后没有断链：" + resolved);
		assertEquals(1, resolved.stream().filter(other.id()::equals).count());
		assertTrue(resolved.stream().filter(id -> !id.equals(other.id())).allMatch(target.id()::equals));
		assertEquals("a b/新 名.md", vault.find(target.id()).orElseThrow().relativePath());
	}

	@Test
	void newNotePathFollowsTheLink() {
		assertEquals(Optional.of("线代/行列式.md"), NoteLinks.newNotePath("线代/特征值.md", "wiki:行列式"));
		assertEquals(Optional.of("数学/行列式.md"), NoteLinks.newNotePath("线代/特征值.md", "wiki:数学/行列式#定义"));
		assertEquals(Optional.of("数学/行 列.md"), NoteLinks.newNotePath("线代/特征值.md", "../数学/行%20列.md"));
		assertEquals(Optional.of("行列式.md"), NoteLinks.newNotePath("欢迎.md", "行列式"));
		assertEquals(Optional.empty(), NoteLinks.newNotePath("欢迎.md", "../外面.md"));
		assertEquals(Optional.empty(), NoteLinks.newNotePath("欢迎.md", "https://example.invalid/a.md"));
	}
}
