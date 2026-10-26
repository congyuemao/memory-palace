package com.memorypalace.core.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MathRendererTest {
	private static final int BLACK = 0xFF000000;

	@BeforeAll
	static void headless() {
		// 游戏里就是 headless 模式
		System.setProperty("java.awt.headless", "true");
	}

	private static boolean hasInk(Raster r) {
		for (int p : r.argb()) {
			if ((p >>> 24) > 0x80) return true;
		}
		return false;
	}

	@Test
	void rendersSimpleFormula() {
		var result = MathRenderer.render("A\\mathbf{v} = \\lambda \\mathbf{v}", false, 20, BLACK);
		assertTrue(result.ok(), () -> "error: " + result.error());
		Raster r = result.raster();
		assertTrue(r.width() > r.height(), "这个公式应该是横向的");
		assertTrue(hasInk(r), "应该画出了东西");
		assertTrue(result.depth() >= 0 && result.depth() <= r.height());
	}

	@Test
	void borderIsTransparentAndColorIsUsed() {
		int blue = 0xFF1A3A7A;
		Raster r = MathRenderer.render("x^2", false, 30, blue).raster();
		for (int x = 0; x < r.width(); x++) {
			assertEquals(0, r.get(x, 0) >>> 24, "上边一圈透明");
			assertEquals(0, r.get(x, r.height() - 1) >>> 24, "下边一圈透明");
		}
		boolean found = false;
		for (int p : r.argb()) {
			if ((p >>> 24) == 0xFF) {
				assertEquals(blue & 0xFFFFFF, p & 0xFFFFFF, "不透明像素用指定颜色");
				found = true;
			}
		}
		assertTrue(found);
	}

	@Test
	void sizeScalesWithPixelSize() {
		Raster small = MathRenderer.render("\\frac{a}{b}", false, 10, BLACK).raster();
		Raster big = MathRenderer.render("\\frac{a}{b}", false, 40, BLACK).raster();
		assertTrue(big.height() > small.height() * 2, small.height() + " vs " + big.height());
	}

	@Test
	void displayStyleIsTallerForSums() {
		Raster inline = MathRenderer.render("\\sum_{i=1}^{n} i", false, 20, BLACK).raster();
		Raster display = MathRenderer.render("\\sum_{i=1}^{n} i", true, 20, BLACK).raster();
		assertTrue(display.height() > inline.height());
	}

	@Test
	void errorsDoNotThrow() {
		for (String bad : new String[]{"\\frac{1}", "\\notacommand{x}", "", "   ", null}) {
			var result = assertDoesNotThrow(() -> MathRenderer.render(bad, false, 20, BLACK));
			assertFalse(result.ok(), "应该失败：" + bad);
			assertNotNull(result.error());
		}
	}

	@Test
	void threadSafe() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<MathRenderer.Result>> futures = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				String tex = "\\sqrt{" + i + "} + \\alpha_" + i;
				boolean display = i % 2 == 0;
				futures.add(pool.submit(() -> MathRenderer.render(tex, display, 18, BLACK)));
			}
			for (var f : futures) {
				assertTrue(f.get().ok());
			}
		} finally {
			pool.shutdown();
		}
	}
}
