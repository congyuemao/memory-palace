package com.memorypalace.client.book;

import com.memorypalace.client.book.BookLayout.BookLine;
import com.memorypalace.client.crypto.ClientKeys;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import com.memorypalace.client.render.NoteImageStore;
import com.memorypalace.client.render.RasterTextures;
import com.memorypalace.core.markdown.MarkdownParser;
import com.memorypalace.core.render.MathRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * "摊开的原版书"界面的公共部分：书页大小、画书页（九宫格）、Markdown 排版、画文字/公式/图片。
 * 阅读界面（NoteScreen）和编辑界面（EditorScreen）都继承它。
 *
 * 坐标约定："页内坐标"从书脊往外量：x=0 是书脊，右页向右为正；左页是右页的镜像。
 */
public abstract class BookScreen extends Screen {
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
	protected static final int TEXT_LEFT = BookViewScreen.PAGE_TEXT_X_OFFSET - U_SPINE;  // 16
	private static final int TEXT_RIGHT_MARGIN = 15;
	protected static final int TEXT_TOP = BookViewScreen.PAGE_TEXT_Y_OFFSET - V_TOP;     // 29
	private static final int TEXT_BOTTOM_MARGIN = 24;
	protected static final int INDICATOR_Y = BookViewScreen.PAGE_INDICATOR_TEXT_Y_OFFSET - V_TOP;

	// 渲染失败过的公式（只在渲染线程用）
	private static final java.util.Set<String> FAILED_MATH = new java.util.HashSet<>();

	/** 上锁时调用：这里存着公式原文。 */
	public static void clearMathFailures() {
		FAILED_MATH.clear();
	}

	protected final UUID noteId; // 没有对应笔记（M 键说明页）时为 null

	// 这次 init 时算好的尺寸（屏幕像素）
	protected int pageW = VANILLA_W, pageH = VANILLA_H, top;
	protected float fontScale = 1.0F;

	protected final boolean encrypted;

	protected BookScreen(UUID noteId, Component title, boolean encrypted) {
		super(title);
		this.noteId = noteId;
		this.encrypted = encrypted;
	}

	public UUID noteId() {
		return noteId;
	}

	/** 正在显示加密笔记的明文（上锁时要关掉这个界面）。 */
	public boolean isEncrypted() {
		return encrypted;
	}

	// 在加密笔记里有任何操作都算"还在用"，推迟自动上锁

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.charTyped(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (encrypted) ClientKeys.touch();
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (encrypted) ClientKeys.touch();
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	// 用输入法拼字时（拼音还在候选框里），游戏收不到"打字"，但这也是在写
	@Override
	public boolean preeditUpdated(PreeditEvent event) {
		if (encrypted) ClientKeys.touch();
		return super.preeditUpdated(event);
	}

	// 移动鼠标也算在用（比如正在看书、准备翻页）
	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (encrypted) ClientKeys.touch();
		super.mouseMoved(mouseX, mouseY);
	}

	/**
	 * 快要自动上锁时，在书页顶部画倒计时提醒（"30 秒后自动上锁，按任意键继续"）。
	 * 子类在画完自己的内容后调用。
	 */
	protected void drawLockWarning(GuiGraphicsExtractor graphics) {
		if (!encrypted) {
			return;
		}
		long seconds = ClientKeys.secondsUntilLock();
		if (seconds < 0 || seconds > ClientKeys.WARNING_SECONDS) {
			return;
		}
		Component warning = Component.translatable("screen.memorypalace.lock_warning", seconds);
		int y = Math.max(3, top - 12);
		int w = this.font.width(warning);
		// 深色底：书本贴着屏幕顶部时，文字会压在书框上
		graphics.fill(spineX() - w / 2 - 4, y - 3, spineX() + w / 2 + 4, y + 11, 0xC0000000);
		graphics.centeredText(this.font, warning, spineX(), y, 0xFFFFD040);
	}

	// ---- 尺寸 ----

	protected void computeSize() {
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

	protected int textWidth() {
		return pageW - TEXT_LEFT - TEXT_RIGHT_MARGIN;
	}

	protected int textHeight() {
		return pageH - TEXT_TOP - TEXT_BOTTOM_MARGIN;
	}

	protected int spineX() {
		return this.width / 2;
	}

	/** 左页文字区左边缘的屏幕 x。 */
	protected int leftTextX() {
		return spineX() - TEXT_LEFT - textWidth();
	}

	/** 右页文字区左边缘的屏幕 x。 */
	protected int rightTextX() {
		return spineX() + TEXT_LEFT;
	}

	// ---- 排版 ----

	/** 把 Markdown 正文排成一页一页（按当前书页大小、字体大小）。 */
	protected List<List<BookLine>> layout(String body) {
		return BookLayout.paginate(this.font, MarkdownParser.parse(body),
				(int) (textWidth() / fontScale), (int) (textHeight() / fontScale), new ScreenBoxes());
	}

	/** 图片到了要重新排版：子类在这里重新调用 layout。 */
	protected abstract void relayout();

	/**
	 * 加密笔记界面在已上锁时不能显示：不管是从"确认放弃修改"对话框、设置页还是别的地方返回，
	 * 每次（重新）打开都检查一遍。子类在 init 开头调用，返回 true 时直接 return。
	 */
	protected boolean closeIfLocked() {
		if (encrypted && !ClientKeys.unlocked()) {
			this.minecraft.execute(() -> this.minecraft.gui.setScreen(null));
			return true;
		}
		return false;
	}

	@Override
	protected void init() {
		// 图片到了就重新排版（排版器会拿到新贴图）
		NoteImageStore.setOnArrive(this::relayout);
		computeSize();
	}

	@Override
	public void removed() {
		NoteImageStore.setOnArrive(null);
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
				// 写错的公式也记住，编辑器实时预览时不用每帧重新试一遍（打到一半的 \lam 之类）
				if (FAILED_MATH.contains(key)) {
					return null;
				}
				var result = MathRenderer.render(tex, display, MATH_SIZE * px, BookLayout.MATH);
				if (!result.ok()) {
					if (FAILED_MATH.size() > 1024) {
						FAILED_MATH.clear();
					}
					FAILED_MATH.add(key);
					return null;
				}
				t = RasterTextures.put(key, result.raster(), result.depth());
			}
			return new BookLayout.Box(t.id(), t.rasterW(), t.rasterH(), t.depth(), px);
		}

		@Override
		public BookLayout.Box image(String src) {
			// 加密笔记里的图片路径来自解密后的正文，不能发给服务端；图片文件本身也没加密。暂不支持
			if (encrypted || noteId == null || NoteImageStore.state(noteId, src) != NoteImageStore.State.READY) {
				return null;
			}
			RasterTextures.Tex t = NoteImageStore.texture(noteId, src);
			return t == null ? null : new BookLayout.Box(t.id(), t.rasterW(), t.rasterH(), 0, pxPerUnit());
		}

		@Override
		public String imagePlaceholder(String src, String alt) {
			if (encrypted) {
				return Component.translatable("screen.memorypalace.image_encrypted").getString();
			}
			if (noteId != null && NoteImageStore.state(noteId, src) == NoteImageStore.State.LOADING) {
				return Component.translatable("screen.memorypalace.image_loading").getString();
			}
			return Component.translatable("screen.memorypalace.image_missing", alt.isEmpty() ? src : alt).getString();
		}
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

	// ---- 画排好的一页 ----

	protected void drawPage(GuiGraphicsExtractor graphics, List<BookLine> page, int screenX) {
		graphics.pose().pushMatrix();
		graphics.pose().translate(screenX, top + TEXT_TOP);
		graphics.pose().scale(fontScale, fontScale);
		int width = (int) (textWidth() / fontScale);
		int y = 0;
		for (BookLine line : page) {
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

	// 单人游戏里打开笔记时不暂停游戏
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
