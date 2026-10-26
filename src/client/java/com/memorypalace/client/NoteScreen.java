package com.memorypalace.client;

import com.memorypalace.client.book.BookLayout;
import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.book.BookSettings;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.client.render.RasterTextures;
import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.render.MathRenderer;
import com.memorypalace.core.vault.Vault;
import com.memorypalace.mc.PalaceServer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 笔记窗口：画成一本摊开的原版书（左右两页），Markdown 排版，翻页按钮 / 滚轮翻页。
 * 书页大小、字体大小在设置里调（SettingsScreen）。书页变大时字号不变、一页的行数变多。
 *
 * 坐标约定："页内坐标"从书脊往外量：x=0 是书脊，右页向右为正；左页是右页的镜像。
 */
public class NoteScreen extends Screen {
	// ---- 原版 book.png 的九宫格切法（单位：贴图像素）----
	// 不透明部分 x=20..165、y=1..181，书脊在左边。红色缝线每 12 像素一针，从 y=28 开始很规整。
	private static final float TEX = 256.0F;
	private static final int U_SPINE = 20, U_INNER = 36, U_OUTER = 148, U_EDGE = 166;
	private static final int V_TOP = 1, V_TILE = 28, V_TILE_END = 88, V_BOTTOM = 148, V_END = 182;
	private static final int SPINE_W = U_INNER - U_SPINE;      // 16：书脊边（含缝线），固定宽
	private static final int EDGE_W = U_EDGE - U_OUTER;        // 18：外侧书页边，固定宽
	private static final int TOP_H = V_TILE - V_TOP;           // 27：上边框，固定高
	private static final int BOTTOM_H = V_END - V_BOTTOM;      // 34：下边框（含书角卷边），固定高
	private static final int VANILLA_W = U_EDGE - U_SPINE;     // 146：原版一页宽
	private static final int VANILLA_H = V_END - V_TOP;        // 181：原版一页高

	// 文字区在页内的位置，和原版一致（原版：贴图 x=36..150，y=30 起）
	private static final int TEXT_LEFT = BookViewScreen.PAGE_TEXT_X_OFFSET - U_SPINE;  // 16
	private static final int TEXT_RIGHT_MARGIN = 15;
	private static final int TEXT_TOP = BookViewScreen.PAGE_TEXT_Y_OFFSET - V_TOP;     // 29
	private static final int TEXT_BOTTOM_MARGIN = 24;
	private static final int INDICATOR_Y = BookViewScreen.PAGE_INDICATOR_TEXT_Y_OFFSET - V_TOP;

	private final UUID noteId; // M 键打开的空白窗口为 null
	private String body;
	private List<List<BookLine>> pages = List.of();
	private int spread; // 第几个"对开页"，0 = 第 1、2 页

	// 这次 init 时算好的尺寸（屏幕像素）
	private int pageW = VANILLA_W, pageH = VANILLA_H, top;
	private float fontScale = 1.0F;

	private PageButton backButton;
	private PageButton forwardButton;

	/** 按 M 打开的窗口：还没有选笔记时显示说明。 */
	public NoteScreen() {
		this(null, Component.translatable("screen.memorypalace.note"),
				Component.translatable("screen.memorypalace.empty_hint").getString());
	}

	public NoteScreen(UUID noteId, Component title, String body) {
		super(title);
		this.noteId = noteId;
		this.body = body;
	}

	public UUID noteId() {
		return noteId;
	}

	/** 文件在外部被改了：换上新内容，尽量停在原来那一页。 */
	public void updateBody(String newBody) {
		this.body = newBody;
		// 外部可能换了图片：重新请求这篇笔记的图
		NoteImageStore.forget(noteId);
		relayout();
	}

	// ---- 尺寸 ----

	private void computeSize() {
		int maxW = Math.max(VANILLA_W, (this.width - 16) / 2);
		int maxH = Math.max(VANILLA_H, this.height - 12);
		float fill = BookSettings.pageSize().fill;
		if (fill == 0) {
			pageW = VANILLA_W;
			pageH = VANILLA_H;
		} else {
			pageW = Math.max(VANILLA_W, Math.round(maxW * fill));
			pageH = Math.max(VANILLA_H, Math.round(maxH * fill));
		}
		// 窗口比原版书还小时，宁可超出也别算出负数
		pageW = Math.min(pageW, maxW);
		pageH = Math.min(pageH, maxH);
		top = Math.max(2, (this.height - pageH) / 2);
		fontScale = BookSettings.fontSize().scale;
	}

	private int textWidth() {
		return pageW - TEXT_LEFT - TEXT_RIGHT_MARGIN;
	}

	private int textHeight() {
		return pageH - TEXT_TOP - TEXT_BOTTOM_MARGIN;
	}

	private int spineX() {
		return this.width / 2;
	}

	// ---- 界面 ----

	@Override
	protected void init() {
		// 图片到了就重新排版（排版器会拿到新贴图）
		NoteImageStore.setOnArrive(this::relayout);
		computeSize();
		relayout();
		int buttonY = top + pageH - 25;
		// 原版翻页按钮（自带翻书声），放在左页左下角和右页右下角
		backButton = addRenderableWidget(new PageButton(spineX() - TEXT_LEFT - textWidth(), buttonY, false, b -> flip(-1), true));
		forwardButton = addRenderableWidget(new PageButton(spineX() + TEXT_LEFT + textWidth() - 23, buttonY, true, b -> flip(1), true));

		// 单人游戏才能直接打开本机文件；做成书脊下方的小按钮，不占阅读空间
		if (noteId != null && this.minecraft != null && this.minecraft.hasSingleplayerServer()) {
			Component label = Component.translatable("screen.memorypalace.open_external");
			addRenderableWidget(Button.builder(Component.literal("✎"), b -> openExternal())
					.bounds(spineX() - 10, Math.min(this.height - 21, top + pageH + 1), 20, 20)
					.tooltip(Tooltip.create(label)).build());
		}
		updateButtons();
	}

	private void relayout() {
		if (this.font == null) {
			return; // 还没 init，init 时会再排一次
		}
		// 字体放大 fontScale 倍 = 排版时把页面当成缩小 fontScale 倍来排
		pages = BookLayout.paginate(this.font, MarkdownParser.parse(body),
				(int) (textWidth() / fontScale), (int) (textHeight() / fontScale), new ScreenBoxes());
		spread = Math.clamp(spread, 0, maxSpread());
		updateButtons();
	}

	/** 1 个排版单位 = 多少个屏幕实际像素。公式按这个分辨率生成，画出来才清楚。 */
	private float pxPerUnit() {
		return (float) (this.minecraft.getWindow().getGuiScale() * fontScale);
	}

	/** 给排版器提供公式和图片的贴图。 */
	private final class ScreenBoxes implements BookLayout.Boxes {
		// 公式字号（排版单位）：大写字母高度和原版字体差不多
		private static final float MATH_SIZE = 10.0F;

		@Override
		public BookLayout.Box math(String tex, boolean display) {
			float px = pxPerUnit();
			String key = "math|" + display + "|" + px + "|" + tex;
			RasterTextures.Tex t = RasterTextures.get(key);
			if (t == null) {
				var result = MathRenderer.render(tex, display, MATH_SIZE * px, BookLayout.MATH);
				if (!result.ok()) {
					return null;
				}
				t = RasterTextures.put(key, result.raster(), result.depth());
			}
			return new BookLayout.Box(t.id(), t.rasterW(), t.rasterH(), t.depth(), px);
		}

		@Override
		public BookLayout.Box image(String src) {
			if (noteId == null || NoteImageStore.state(noteId, src) != NoteImageStore.State.READY) {
				return null;
			}
			RasterTextures.Tex t = NoteImageStore.texture(noteId, src);
			// 图片按实际像素大小显示（太大会被排版器缩到页宽）
			return t == null ? null : new BookLayout.Box(t.id(), t.rasterW(), t.rasterH(), 0, pxPerUnit());
		}

		@Override
		public String imagePlaceholder(String src, String alt) {
			if (noteId != null && NoteImageStore.state(noteId, src) == NoteImageStore.State.LOADING) {
				return Component.translatable("screen.memorypalace.image_loading").getString();
			}
			return Component.translatable("screen.memorypalace.image_missing", alt.isEmpty() ? src : alt).getString();
		}
	}

	@Override
	public void removed() {
		NoteImageStore.setOnArrive(null);
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

	private void openExternal() {
		Vault vault = PalaceServer.vault();
		Optional<Path> path = vault == null ? Optional.empty() : vault.pathOf(noteId);
		path.ifPresent(ExternalEditor::open);
	}

	// ---- 画书页（九宫格平铺，1:1 像素，不拉伸）----

	// 书本贴图画在"背景"这一层，按钮才会盖在书上面
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		drawPageFrame(graphics, false);
		drawPageFrame(graphics, true);
	}

	/** 画一整页。九宫格：3 列（书脊边 / 纸面 / 外侧边）× 3 行（上边 / 中间 / 下边），中间部分重复平铺。 */
	private void drawPageFrame(GuiGraphicsExtractor graphics, boolean mirrored) {
		int[][] cols = {
				{0, SPINE_W, U_SPINE, U_INNER},
				{SPINE_W, pageW - EDGE_W, U_INNER, U_OUTER},
				{pageW - EDGE_W, pageW, U_OUTER, U_EDGE}};
		int[][] rows = {
				{0, TOP_H, V_TOP, V_TILE},
				{TOP_H, pageH - BOTTOM_H, V_TILE, V_TILE_END},
				{pageH - BOTTOM_H, pageH, V_BOTTOM, V_END}};
		for (int[] c : cols) {
			for (int[] r : rows) {
				tile(graphics, mirrored, c[0], c[1], c[2], c[3], r[0], r[1], r[2], r[3]);
			}
		}
	}

	/** 在页内区域 [x0,x1)×[y0,y1) 里重复铺贴图区域 [u0,u1)×[v0,v1)，最后一块按需截短。 */
	private void tile(GuiGraphicsExtractor graphics, boolean mirrored,
			int x0, int x1, int u0, int u1, int y0, int y1, int v0, int v1) {
		for (int x = x0; x < x1; x += u1 - u0) {
			int w = Math.min(u1 - u0, x1 - x);
			for (int y = y0; y < y1; y += v1 - v0) {
				int h = Math.min(v1 - v0, y1 - y);
				float ua = u0 / TEX, ub = (u0 + w) / TEX, va = v0 / TEX, vb = (v0 + h) / TEX;
				int sy = top + y;
				if (mirrored) {
					// 左页：屏幕上向左长，贴图 u 反过来（u0 > u1）就是镜像
					int sx = spineX() - x - w;
					graphics.blit(BookViewScreen.BOOK_LOCATION, sx, sy, sx + w, sy + h, ub, ua, va, vb);
				} else {
					int sx = spineX() + x;
					graphics.blit(BookViewScreen.BOOK_LOCATION, sx, sy, sx + w, sy + h, ua, ub, va, vb);
				}
			}
		}
	}

	// ---- 画文字 ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int leftX = spineX() - TEXT_LEFT - textWidth();
		int rightX = spineX() + TEXT_LEFT;
		int leftIndex = spread * 2;
		drawPage(graphics, leftIndex, leftX);
		drawPage(graphics, leftIndex + 1, rightX);

		// 页码，位置和原版的"第 x 页"一样在页面上方（不跟字体一起放大）
		int indicatorY = top + INDICATOR_Y;
		if (leftIndex < pages.size()) {
			graphics.text(this.font, String.valueOf(leftIndex + 1), leftX, indicatorY, BookLayout.MUTED, false);
		}
		if (leftIndex + 1 < pages.size()) {
			String n = (leftIndex + 2) + " / " + pages.size();
			graphics.text(this.font, n, rightX + textWidth() - this.font.width(n), indicatorY, BookLayout.MUTED, false);
		}
	}

	private void drawPage(GuiGraphicsExtractor graphics, int index, int screenX) {
		if (index >= pages.size()) {
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(screenX, top + TEXT_TOP);
		graphics.pose().scale(fontScale, fontScale);
		int width = (int) (textWidth() / fontScale);
		int y = 0;
		for (BookLine line : pages.get(index)) {
			switch (line.kind()) {
				case CODE -> graphics.fill(0, y - 1, width, y + line.height(), 0x1A3A2A10);
				case QUOTE -> {
					for (int d = 0; d < line.depth(); d++) {
						int bx = d * 6 + 1;
						graphics.fill(bx, y - 1, bx + 2, y + line.height(), 0x55704A2A);
					}
				}
				case RULE -> graphics.fill(0, y + 3, width, y + 4, 0x66704A2A);
				default -> {}
			}
			for (BookLayout.Seg seg : line.segs()) {
				switch (seg) {
					// 原版书里的字没有阴影
					case BookLayout.TextSeg t -> graphics.text(this.font, t.text(), t.x(), y + t.y(), BookLayout.TEXT, false);
					case BookLayout.BoxSeg b -> drawBox(graphics, b, y);
				}
			}
			y += line.height();
		}
		graphics.pose().popMatrix();
	}

	/** 画公式/图片：缩到"1 贴图像素 = 1 屏幕像素"再画，保证清晰。 */
	private void drawBox(GuiGraphicsExtractor graphics, BookLayout.BoxSeg seg, int lineY) {
		BookLayout.Box box = seg.box();
		graphics.pose().pushMatrix();
		graphics.pose().translate(seg.x(), lineY + seg.y());
		float s = seg.scale() / box.pxPerUnit();
		graphics.pose().scale(s, s);
		graphics.blit(RenderPipelines.GUI_TEXTURED, box.texture(), 0, 0, 0.0F, 0.0F,
				box.rasterW(), box.rasterH(), box.rasterW(), box.rasterH());
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			flip(scrollY < 0 ? 1 : -1);
		}
		return true;
	}

	// 单人游戏里打开笔记时不暂停游戏
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
