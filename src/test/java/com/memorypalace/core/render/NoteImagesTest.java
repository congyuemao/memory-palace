package com.memorypalace.core.render;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NoteImagesTest {
	@TempDir
	Path dir;

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private Path vault() {
		return dir.resolve("vault");
	}

	private Path png(String rel, int w, int h, int argb) throws IOException {
		Path p = vault().resolve(rel);
		Files.createDirectories(p.getParent());
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
		ImageIO.write(img, "png", p.toFile());
		return p;
	}

	private Optional<Path> resolve(String src) {
		return NoteImages.resolve(vault(), "线代/特征值.md", src);
	}

	@Test
	void resolvesRelativeToNoteThenRootThenByName() throws IOException {
		Path nextToNote = png("线代/图.png", 2, 2, 0xFFFF0000);
		Path atRoot = png("assets/示意图.png", 2, 2, 0xFF00FF00);
		Path deep = png("附件/深处/仅此一张.png", 2, 2, 0xFF0000FF);
		png(".obsidian/仅此一张2.png", 2, 2, 0xFF0000FF);

		assertEquals(nextToNote.toRealPath(), resolve("图.png").orElseThrow().toRealPath());
		assertEquals(atRoot.toRealPath(), resolve("assets/示意图.png").orElseThrow().toRealPath());
		assertEquals(deep.toRealPath(), resolve("仅此一张.png").orElseThrow().toRealPath(), "Obsidian 按文件名找");
		assertTrue(resolve("仅此一张2.png").isEmpty(), "隐藏文件夹里的不算");
		assertTrue(resolve("不存在.png").isEmpty());
	}

	@Test
	void urlEncodedNames() throws IOException {
		Path p = png("线代/my pic.png", 2, 2, 0xFFFFFFFF);
		assertEquals(p.toRealPath(), resolve("my%20pic.png").orElseThrow().toRealPath());
	}

	@Test
	void rejectsRemoteAbsoluteEscapingAndWrongTypes() throws IOException {
		png("线代/图.png", 2, 2, 0xFFFF0000);
		Files.writeString(vault().resolve("线代/notes.txt"), "x");
		Files.createDirectories(dir);
		png("../outside.png", 2, 2, 0xFFFF0000); // 写到 vault 外面的 dir/outside.png

		assertTrue(resolve("https://example.com/a.png").isEmpty());
		assertTrue(resolve("http://example.com/a.png").isEmpty());
		assertTrue(resolve("data:image/png;base64,AAAA").isEmpty());
		assertTrue(resolve("file:///C:/a.png").isEmpty());
		assertTrue(resolve("C:/Windows/a.png").isEmpty());
		assertTrue(resolve(vault().resolve("线代/图.png").toAbsolutePath().toString()).isEmpty());
		assertTrue(resolve("../../outside.png").isEmpty());
		assertTrue(resolve("notes.txt").isEmpty());
		assertTrue(resolve(null).isEmpty());
	}

	@Test
	void loadDownscalesAndKeepsAlpha() throws IOException {
		Path big = png("big.png", 400, 100, 0x8000FF00);
		Raster r = NoteImages.load(big, 200).orElseThrow();
		assertEquals(200, r.width());
		assertEquals(50, r.height());
		int p = r.get(100, 25);
		assertTrue(Math.abs((p >>> 24) - 0x80) <= 2, "半透明保留");

		Path small = png("small.png", 10, 20, 0xFF123456);
		Raster s = NoteImages.load(small, 200).orElseThrow();
		assertEquals(10, s.width(), "小图不放大");
		assertEquals(0xFF123456, s.get(5, 5));
	}

	@Test
	void loadBadFileIsEmpty() throws IOException {
		Path bad = vault().resolve("坏.png");
		Files.createDirectories(bad.getParent());
		Files.write(bad, new byte[]{1, 2, 3});
		assertTrue(NoteImages.load(bad, 100).isEmpty());
		assertTrue(NoteImages.fromBytes(new byte[]{1, 2, 3}).isEmpty());
	}

	@Test
	void pngRoundTrip() {
		int[] px = {0xFFFF0000, 0x8000FF00, 0x00000000, 0xFF0000FF};
		Raster r = new Raster(2, 2, px);
		Raster back = NoteImages.fromBytes(NoteImages.toPng(r)).orElseThrow();
		assertEquals(2, back.width());
		assertArrayEquals(px, back.argb());
	}
}
