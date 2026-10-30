package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookScreen;
import com.memorypalace.client.crypto.ClientKeys;
import com.memorypalace.client.crypto.CryptoClient;
import com.memorypalace.client.crypto.NoteSealers;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.core.markdown.MdBlock;
import com.memorypalace.core.markdown.MdSpan;
import com.memorypalace.core.vault.NoteLinks;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.LinkPayloads;
import com.memorypalace.mc.OpenNotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 阅读笔记：摊开的原版书（左右两页），翻页按钮 / 滚轮翻页。书脊下方的 ✎ 进入游戏内编辑器。
 * 书页大小、字体大小在设置里调（SettingsScreen）。
 */
public class NoteScreen extends BookScreen {
	private String body;          // 明文（加密笔记已经解密）
	private String storedHash;    // 文件里存的那份正文（加密笔记是密文）的指纹，编辑器保存时检查冲突用
	private final boolean editable; // 服务端说这个人能改这篇：只决定显不显示编辑按钮，保存时服务端还会再检查
	private List<List<BookLine>> pages = List.of();
	private int spread; // 第几个"对开页"，0 = 第 1、2 页

	private PageButton backButton;
	private PageButton forwardButton;

	// 服务端整理好的链接情况（见 LinkPayloads.Info）：断链换颜色，反向链接列在正文后面。打开后要一次，正文变了再要一次
	private Set<String> brokenLinks = Set.of();
	private LinkPayloads.Info linkInfo;
	private boolean linkInfoRequested;

	/** 按 M 打开的窗口：还没有选笔记时显示说明。 */
	public NoteScreen() {
		this(null, Component.translatable("screen.memorypalace.note"),
				Component.translatable("screen.memorypalace.empty_hint").getString());
	}

	public NoteScreen(UUID noteId, Component title, String body) {
		this(noteId, title, body, false, Vault.hash(body), false);
	}

	public NoteScreen(UUID noteId, Component title, String body, boolean encrypted, String storedHash, boolean editable) {
		super(noteId, title, encrypted);
		this.body = body;
		this.storedHash = storedHash;
		this.editable = editable;
	}

	/** 文件在外部被改了：换上新内容，尽量停在原来那一页。 */
	public void updateBody(String newBody, String newStoredHash) {
		this.body = newBody;
		this.storedHash = newStoredHash;
		// 外部可能换了图片：重新请求这篇笔记的图
		NoteImageStore.forget(noteId);
		requestLinkInfo();
		relayout();
	}

	private void requestLinkInfo() {
		if (noteId != null && ClientPlayNetworking.canSend(LinkPayloads.InfoRequest.TYPE)) {
			linkInfoRequested = true;
			ClientPlayNetworking.send(new LinkPayloads.InfoRequest(noteId));
		}
	}

	/** 服务端发来的链接情况：正打开着的是这篇就重新排版。 */
	public static void onLinkInfo(LinkPayloads.Info info) {
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof NoteScreen screen && info.noteId().equals(screen.noteId)) {
			screen.linkInfo = info;
			screen.brokenLinks = Set.copyOf(info.broken());
			screen.relayout();
		}
	}

	@Override
	protected boolean brokenLink(String link) {
		return brokenLinks.contains(link);
	}

	/** 正文后面接的"链接到本篇的笔记"：分隔线、小标题、每篇一行（点了就打开）。没有反向链接时不加。 */
	private List<MdBlock> backlinkBlocks() {
		if (linkInfo == null || linkInfo.backlinks().isEmpty()) {
			return List.of();
		}
		List<MdBlock> blocks = new ArrayList<>();
		blocks.add(new MdBlock.Rule());
		blocks.add(new MdBlock.Heading(3, List.of(MdSpan.plain(
				Component.translatable("screen.memorypalace.backlinks", linkInfo.backlinks().size() + linkInfo.moreBacklinks()).getString()))));
		for (LinkPayloads.Backlink link : linkInfo.backlinks()) {
			String path = link.path();
			String target = path.toLowerCase(java.util.Locale.ROOT).endsWith(".md") ? path.substring(0, path.length() - 3) : path;
			String shown = link.title().isBlank() ? target : link.title();
			blocks.add(new MdBlock.ListItem(0, "•", null, List.of(new MdSpan(shown, 0, NoteLinks.WIKI + target))));
		}
		if (linkInfo.moreBacklinks() > 0) {
			blocks.add(new MdBlock.Paragraph(List.of(MdSpan.plain(
					Component.translatable("screen.memorypalace.backlinks_more", linkInfo.moreBacklinks()).getString()))));
		}
		return blocks;
	}

	@Override
	protected void init() {
		super.init();
		if (closeIfLocked()) {
			return;
		}
		if (!linkInfoRequested) {
			requestLinkInfo();
		}
		relayout();
		int buttonY = top + pageH - 25;
		// 原版翻页按钮（自带翻书声），放在左页左下角和右页右下角
		backButton = addRenderableWidget(new PageButton(leftTextX(), buttonY, false, b -> flip(-1), true));
		forwardButton = addRenderableWidget(new PageButton(rightTextX() + textWidth() - 23, buttonY, true, b -> flip(1), true));

		// ✎ 进入游戏内编辑器；做成书脊下方的小按钮，不占阅读空间
		if (noteId != null && editable) {
			int y = Math.min(this.height - 21, top + pageH + 1);
			addRenderableWidget(Button.builder(Component.literal("✎"), b -> openEditor())
					.bounds(spineX() - 10, y, 20, 20)
					.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.edit"))).build());
			// 解除加密只能从"正打开着的这篇"发起：客户端记下这份密文的指纹，服务端发回的必须是同一份
			if (encrypted) {
				addRenderableWidget(Button.builder(Component.translatable("screen.memorypalace.decrypt_short"),
								b -> CryptoClient.requestDecrypt(noteId, storedHash))
						.bounds(spineX() + 14, y, 40, 20)
						.tooltip(Tooltip.create(Component.translatable("screen.memorypalace.decrypt_tooltip"))).build());
			}
		}
		updateButtons();
	}

	@Override
	protected void relayout() {
		if (this.font == null) {
			return; // 还没 init，init 时会再排一次
		}
		pages = layout(body, backlinkBlocks());
		spread = Math.clamp(spread, 0, maxSpread());
		updateButtons();
	}

	private void openEditor() {
		// 游戏内输入框处理不了太长的文本，超过上限会被截断——截断后一保存就丢了后半段，所以干脆不打开
		if (body.length() > OpenNotePayload.MAX_BODY) {
			this.minecraft.player.sendSystemMessage(Component.translatable("message.memorypalace.too_long_to_edit", OpenNotePayload.MAX_BODY));
			return;
		}
		this.minecraft.gui.setScreen(new EditorScreen(noteId, this.title, body, encrypted, storedHash));
	}

	/**
	 * 点书页上的链接：笔记链接交给服务端找到那篇打开（读不了的和不存在的一样说找不到）；
	 * 断链先问要不要新建；网址先弹原版的确认框，确认后用浏览器打开。
	 */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == LEFT_BUTTON && noteId != null) {
			int leftIndex = spread * 2;
			String link = leftIndex < pages.size() ? linkAt(pages.get(leftIndex), leftTextX(), event.x(), event.y()) : null;
			if (link == null && leftIndex + 1 < pages.size()) {
				link = linkAt(pages.get(leftIndex + 1), rightTextX(), event.x(), event.y());
			}
			if (link != null) {
				openLink(link);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	// 26.x 底层换成了 SDL：左键是 1（不是 GLFW 的 0），原版按钮也按 1 判断
	private static final int LEFT_BUTTON = 1;

	private void openLink(String link) {
		if (com.memorypalace.core.vault.NoteLinks.isExternal(link)) {
			java.net.URI uri;
			try {
				uri = new java.net.URI(link);
			} catch (java.net.URISyntaxException e) {
				return;
			}
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
			if (scheme.equals("http") || scheme.equals("https")) {
				net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, uri);
			}
			return;
		}
		if (link.length() > LinkPayloads.MAX_LINK) {
			return;
		}
		if (brokenLinks.contains(link) && ClientPlayNetworking.canSend(LinkPayloads.CreateFromLink.TYPE)) {
			String name = link.startsWith(NoteLinks.WIKI) ? link.substring(NoteLinks.WIKI.length()) : link;
			this.minecraft.gui.setScreen(new ConfirmScreen(create -> {
						this.minecraft.gui.setScreen(this);
						if (create) {
							ClientPlayNetworking.send(new LinkPayloads.CreateFromLink(noteId, link));
						}
					},
					Component.translatable("screen.memorypalace.broken_link_title"),
					Component.translatable("screen.memorypalace.broken_link_message", name),
					Component.translatable("screen.memorypalace.broken_link_create"),
					Component.translatable("screen.memorypalace.broken_link_cancel")));
			return;
		}
		ClientPlayNetworking.send(new LinkPayloads.OpenLink(noteId, link));
	}

	private int maxSpread() {
		return Math.max(0, (pages.size() - 1) / 2);
	}

	private void flip(int delta) {
		spread = Math.clamp(spread + delta, 0, maxSpread());
		updateButtons();
	}

	private void updateButtons() {
		if (backButton != null) {
			backButton.visible = spread > 0;
			forwardButton.visible = spread < maxSpread();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		if (encrypted && !NoteSealers.available(noteId)) {
			return; // 已上锁：一帧都不画明文（界面马上会被关掉）
		}
		int leftIndex = spread * 2;
		if (leftIndex < pages.size()) {
			drawPage(graphics, pages.get(leftIndex), leftTextX());
		}
		if (leftIndex + 1 < pages.size()) {
			drawPage(graphics, pages.get(leftIndex + 1), rightTextX());
		}

		// 页码，位置和原版的"第 x 页"一样在页面上方（不跟字体一起放大）
		int indicatorY = top + INDICATOR_Y;
		if (leftIndex < pages.size()) {
			graphics.text(this.font, String.valueOf(leftIndex + 1), leftTextX(), indicatorY, BookLayout.MUTED, false);
		}
		if (leftIndex + 1 < pages.size()) {
			String n = (leftIndex + 2) + " / " + pages.size();
			graphics.text(this.font, n, rightTextX() + textWidth() - this.font.width(n), indicatorY, BookLayout.MUTED, false);
		}
		drawLockWarning(graphics);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (scrollY != 0) {
			flip(scrollY < 0 ? 1 : -1);
		}
		return true;
	}
}
