package com.memorypalace.core.render;

/**
 * 一张位图：width × height 个像素，argb 按行存（第 y 行第 x 列 = argb[y * width + x]），每个像素 0xAARRGGBB。
 * 公式和图片都先变成 Raster，客户端再把它转成游戏贴图。
 */
public record Raster(int width, int height, int[] argb) {
	public Raster {
		if (width <= 0 || height <= 0 || argb.length != width * height) {
			throw new IllegalArgumentException("bad raster " + width + "x" + height + " / " + argb.length);
		}
	}

	public int get(int x, int y) {
		return argb[y * width + x];
	}
}
