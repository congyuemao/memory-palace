package com.memorypalace.core.vault;

import java.util.UUID;

/**
 * 一篇笔记的"目录卡片"：列表和笔记书上要显示的信息，不含正文。
 * @param id           永久 ID（front matter 里的 palace-id）
 * @param relativePath 相对笔记库根目录的路径，统一用 "/" 分隔，例如 "线性代数/特征值.md"
 * @param title        标题：正文里第一个 "# 一级标题"；没有就用文件名（去掉 .md）
 */
public record NoteSummary(UUID id, String relativePath, String title) {
	/** 所在文件夹，例如 "线性代数"；在根目录时为 ""。 */
	public String folder() {
		int slash = relativePath.lastIndexOf('/');
		return slash < 0 ? "" : relativePath.substring(0, slash);
	}
}
