package com.memorypalace.core.vault;

import java.util.UUID;

/**
 * 一篇笔记的"目录卡片"：列表和笔记书上要显示的信息，不含正文。
 * @param id           永久 ID（front matter 里的 palace-id）
 * @param relativePath 相对笔记库根目录的路径，统一用 "/" 分隔，例如 "线性代数/特征值.md"
 * @param title        标题：正文里第一个 "# 一级标题"；没有就用文件名（去掉 .md）。加密笔记一律用文件名（标题在密文里，不能泄露）
 * @param encrypted    是不是加密笔记（front matter 里有 palace-encrypted，或者正文是密文）
 */
public record NoteSummary(UUID id, String relativePath, String title, boolean encrypted) {
	/** 所在文件夹，例如 "线性代数"；在根目录时为 ""。 */
	public String folder() {
		int slash = relativePath.lastIndexOf('/');
		return slash < 0 ? "" : relativePath.substring(0, slash);
	}
}
