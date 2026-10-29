package com.memorypalace.client.render;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.render.Raster;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Raster（公式、图片）变成游戏贴图并缓存。最多同时保留 MAX 张、合计 MAX_PIXELS 像素，最久没用的会被释放。
 * 只能在渲染线程（客户端主线程）调用。
 */
public final class RasterTextures {
	private static final int MAX = 256;
	// 按像素算总量（约 64MB 显存/内存）：图片来自服务端，不能只数张数，否则几十张大图就能把客户端撑爆
	private static final long MAX_PIXELS = 16L * 1024 * 1024;
	private static long pixels;

	/** 一张贴图：rasterW/H 是贴图本身的像素尺寸，depth 是基线以下的像素数（图片为 0）。 */
	public record Tex(Identifier id, int rasterW, int rasterH, int depth) {}

	// accessOrder = true：每次 get 都会把它挪到最后，所以第一个就是最久没用的
	private static final Map<String, Tex> CACHE = new LinkedHashMap<>(64, 0.75F, true);
	private static int counter;

	private RasterTextures() {}

	public static Tex get(String key) {
		return CACHE.get(key);
	}

	/** 单张最多这么多像素（和公式位图上限一样），超过的不建贴图，返回 null：在分配显存之前就挡住。 */
	private static final long MAX_SINGLE_PIXELS = 4L * 1024 * 1024;

	/** @return 新贴图；位图太大时返回 null（不缓存） */
	public static Tex put(String key, Raster raster, int depth) {
		if ((long) raster.width() * raster.height() > MAX_SINGLE_PIXELS) {
			return null;
		}
		NativeImage image = new NativeImage(raster.width(), raster.height(), false);
		for (int y = 0; y < raster.height(); y++) {
			for (int x = 0; x < raster.width(); x++) {
				image.setPixel(x, y, raster.get(x, y));
			}
		}
		Identifier id = MemoryPalace.id("dynamic/" + (counter++));
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
		Tex tex = new Tex(id, raster.width(), raster.height(), depth);
		Tex old = CACHE.put(key, tex);
		pixels += (long) raster.width() * raster.height();
		if (old != null) {
			release(old);
		}
		while (CACHE.size() > MAX || pixels > MAX_PIXELS && CACHE.size() > 1) {
			var eldest = CACHE.entrySet().iterator().next();
			CACHE.remove(eldest.getKey());
			release(eldest.getValue());
		}
		return tex;
	}

	/** 界面缩放变了等情况：全部清掉，下次重新生成。 */
	public static void clear() {
		CACHE.values().forEach(RasterTextures::release);
		CACHE.clear();
		pixels = 0;
	}

	private static void release(Tex tex) {
		pixels -= (long) tex.rasterW() * tex.rasterH();
		Minecraft.getInstance().getTextureManager().release(tex.id());
	}
}
