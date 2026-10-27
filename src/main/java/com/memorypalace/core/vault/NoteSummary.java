package com.memorypalace.core.vault;

import java.util.UUID;

/**
 * 一篇笔记的"目录卡片"：列表和笔记书上要显示的信息，不含正文。
 * @param id           永久 ID（front matter 里的 palace-id）
 * @param relativePath 相对笔记库根目录的路径，统一用 "/" 分隔，例如 "线性代数/特征值.md"
 * @param title        标题：正文里第一个 "# 一级标题"；没有就用文件名（去掉 .md）。加密笔记一律用文件名（标题在密文里，不能泄露）
 * @param encrypted    是不是加密笔记（front matter 里有 palace-encrypted，或者正文是密文）
 * @param access       谁能读、谁能改（front matter 里的 palace-owner / palace-read / palace-edit，见 NoteAccess）
 */
public record NoteSummary(UUID id, String relativePath, String title, boolean encrypted, NoteAccess.Rules access) {
	/** 所在文件夹，例如 "线性代数"；在根目录时为 ""。 */
	public String folder() {
		int slash = relativePath.lastIndexOf('/');
		return slash < 0 ? "" : relativePath.substring(0, slash);
	}

	/** 收件箱里的笔记（快速收集写进去的）：永远私有。 */
	public boolean inbox() {
		return relativePath.startsWith(Inbox.FOLDER + "/");
	}

	/** 只有作者能碰：收件箱和加密笔记不受 palace-read / palace-edit 和可见性影响。 */
	public boolean ownerOnly() {
		return encrypted || inbox();
	}

	public boolean canRead(NoteAccess.Who who, NoteAccess.Visibility visibility) {
		return NoteAccess.canRead(access, ownerOnly(), who, visibility);
	}

	public boolean canEdit(NoteAccess.Who who) {
		return NoteAccess.canEdit(access, ownerOnly(), who);
	}

	/** 书名、准星提示能不能用正文标题。 */
	public boolean titleIsPublic(NoteAccess.Visibility visibility) {
		return NoteAccess.titleIsPublic(access, ownerOnly(), visibility);
	}
}
