package com.memorypalace.core.render;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.scilab.forge.jlatexmath.ParseException;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXParser;

/**
 * LaTeX 公式 → 位图，底层用 JLaTeXMath（org.scilab.forge.jlatexmath）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class MathRenderer {
	private static final Object RENDER_LOCK = new Object();

	private MathRenderer() {}

	/**
	 * 渲染结果。成功时 raster 非 null、error 为 null；失败时相反。
	 * @param depth 基线以下的像素数（例如 y 的尾巴），用来和文字基线对齐；0 ≤ depth ≤ raster.height
	 */
	public record Result(Raster raster, int depth, String error) {
		public boolean ok() {
			return raster != null;
		}
	}

	/**
	 * 要求：
	 * - display = true 用行间公式样式（$$...$$，例如 \sum 上下标在正上下方），false 用行内样式
	 * - pixelSize 是字号（像素），结果高度大致和它成正比
	 * - 背景透明，公式用 color（0xAARRGGBB）画，开抗锯齿
	 * - 位图四周各留 1 像素透明边，避免抗锯齿边缘被切掉
	 * - 语法错误（例如 "\frac{1}"、未知命令 "\notacommand"）返回 error 为非空的失败结果，绝不抛异常
	 * - null、空串或全是空白 → 失败结果
	 * - 必须能在 java.awt.headless=true 下工作（游戏里就是这样）；不要创建任何窗口或 Swing 组件
	 * - 线程安全（可能同时从多个线程调用）
	 */
	public static Result render(String tex, boolean display, float pixelSize, int color) {
		if (tex == null || tex.isBlank()) {
			return new Result(null, 0, "公式不能为空");
		}
		if (!Float.isFinite(pixelSize) || pixelSize <= 0) {
			return new Result(null, 0, "字号必须是有限正数");
		}
		// JLaTeXMath 的字体缓存和宏表是共享的，解析和绘制都放在锁内。
		synchronized (RENDER_LOCK) {
			try {
				var formula = new TeXFormula();
				new StrictParser(tex, formula).parse();
				var icon = formula.createTeXIcon(
						display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT, pixelSize);
				icon.setForeground(new Color(color, true));
				int width = Math.addExact(icon.getIconWidth(), 2);
				int height = Math.addExact(icon.getIconHeight(), 2);
				var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				var graphics = image.createGraphics();
				try {
					// 1.0.7 会检查 null；TeXIcon 本身不是 Swing 组件。
					icon.paintIcon(null, graphics, 1, 1);
				} finally {
					graphics.dispose();
				}
				return new Result(new Raster(width, height,
						image.getRGB(0, 0, width, height, null, 0, width)),
						Math.clamp((long) icon.getIconDepth() + 1, 0, height), null);
			} catch (RuntimeException e) {
				String message = e.getMessage();
				return new Result(null, 0, message == null || message.isBlank()
						? e.getClass().getSimpleName() : message);
			}
		}
	}

	private static final class StrictParser extends TeXParser {
		private StrictParser(String tex, TeXFormula formula) {
			super(tex, formula);
		}

		@Override
		public String[] getOptsArgs(int count, int optionPosition) {
			String[] args = super.getOptsArgs(count, optionPosition);
			// 1.0.7 将缺失参数返回为 null，显式的空参数 {} 则是空串。
			for (int i = 1; i <= count; i++) {
				if (args[i] == null) {
					throw new ParseException("缺少必需参数 " + i);
				}
			}
			return args;
		}
	}
}
