package com.memorypalace.core.render;

/**
 * LaTeX 公式 → 位图，底层用 JLaTeXMath（org.scilab.forge.jlatexmath）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class MathRenderer {
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
		throw new UnsupportedOperationException("TODO");
	}
}
