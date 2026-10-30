package com.memorypalace.core.vault;

import com.memorypalace.core.search.NoteSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 笔记库的性能基准：2000 篇笔记（每篇约 2 KB，带 [[链接]]）上的常用操作各要多久。
 * 平时不跑；要跑时：./gradlew test --tests "*VaultBenchmarkTest" -Dbenchmark=true（结果打印在测试输出里）。
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class VaultBenchmarkTest {
	private static final int NOTES = 2000;

	@TempDir
	Path dir;

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	@Test
	void benchmark() throws Exception {
		Path root = dir.resolve("vault");
		String filler = "这是一段用来凑长度的正文，记录一些关于矩阵、特征值和记忆宫殿的想法。".repeat(30);
		for (int i = 0; i < NOTES; i++) {
			Path file = root.resolve("文件夹" + (i % 40)).resolve("笔记" + i + ".md");
			Files.createDirectories(file.getParent());
			Files.writeString(file, "---\npalace-id: " + UUID.nameUUIDFromBytes(("n" + i).getBytes()) + "\n---\n# 笔记 " + i
					+ "\n\n见 [[笔记" + (i + 1) % NOTES + "]] 和 [[笔记" + (i * 7) % NOTES + "]]。\n" + filler + (i == 1234 ? "独一无二的词" : "") + "\n");
		}
		Vault vault = new Vault(root);
		StringBuilder report = new StringBuilder("\n===== 笔记库基准（" + NOTES + " 篇）=====\n");

		long t = System.nanoTime();
		vault.scan();
		report.append("首次扫描          ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 200; i++) {
			vault.resolve("文件夹" + (i % 40) + "/笔记" + i);
		}
		report.append("按路径找 ×200     ").append(ms(t)).append(" ms\n");

		List<NoteSummary> all = vault.list();
		t = System.nanoTime();
		for (int i = 0; i < 200; i++) {
			vault.readNote(all.get(i * 7 % NOTES).id());
		}
		report.append("打开笔记 ×200     ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		List<NoteSearch.Hit> hits = NoteSearch.search(vault, "独一无二的词", 20);
		report.append("搜索正文（首次）  ").append(ms(t)).append(" ms，命中 ").append(hits.size()).append('\n');
		t = System.nanoTime();
		for (int i = 0; i < 5; i++) {
			NoteSearch.search(vault, "独一无二的词", 20);
		}
		report.append("搜索正文 ×5       ").append(ms(t)).append(" ms\n");

		NoteSummary target = vault.list().stream().filter(n -> n.relativePath().endsWith("/笔记1000.md")).findFirst().orElseThrow();
		t = System.nanoTime();
		int backlinks = NoteLinks.backlinks(vault, target, n -> true).size();
		report.append("反向链接（首次）  ").append(ms(t)).append(" ms，").append(backlinks).append(" 篇\n");
		t = System.nanoTime();
		for (int i = 0; i < 5; i++) {
			NoteLinks.backlinks(vault, target, n -> true);
		}
		report.append("反向链接 ×5       ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			vault.refreshChanged();
		}
		report.append("后台刷新 ×20      ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			NoteSummary n = all.get(i);
			vault.writeBody(n.id(), "# 改过 " + i + "\n" + filler, null);
		}
		report.append("保存 ×100         ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			vault.createNote("新建/新笔记" + i, "");
		}
		report.append("新建 ×20          ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			NoteSummary n = vault.resolve("新建/新笔记" + i).orElseThrow();
			vault.renameNote(n.id(), "改名" + i);
		}
		report.append("改名 ×20          ").append(ms(t)).append(" ms\n");

		t = System.nanoTime();
		for (int i = 0; i < 10; i++) {
			vault.moveToTrash("新建/改名" + i + ".md", Instant.now());
		}
		report.append("删除 ×10          ").append(ms(t)).append(" ms\n");
		System.out.println(report);
	}
}
