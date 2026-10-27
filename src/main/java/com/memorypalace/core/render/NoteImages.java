package com.memorypalace.core.render;

import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 笔记里引用的本地图片：找文件、解码、缩小、编码成 PNG（用来通过网络发给客户端）。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteImages {
	private NoteImages() {}

	/**
	 * 找到笔记里 src 指向的图片文件。要求：
	 * - src 可能是 URL 编码的（"my%20pic.png"），先解码；解码失败就按原样用
	 * - 以 http:、https:、data:、file: 等任意 "协议:" 开头 → empty（不加载网络/特殊图片；Windows 盘符 "C:" 也算绝对路径 → empty）
	 * - 绝对路径 → empty
	 * - 只接受扩展名 png、jpg、jpeg、gif、bmp（不区分大小写），其他 → empty
	 * - 查找顺序：① 相对笔记所在文件夹 ② 相对笔记库根目录 ③ 在整个笔记库里按文件名找（Obsidian 的习惯；
	 *   多个同名时取相对路径字典序最小的；跳过名字以 "." 开头的文件夹）。第 ③ 步只在 src 不含 "/" 时进行
	 * - 结果必须在笔记库里面（规范化后仍以 vaultRoot 开头，且是普通文件）；含 ".." 跑出去的 → empty
	 * - 任何异常都不抛，返回 empty
	 * @param noteRelativePath 笔记相对笔记库的路径，"/" 分隔，例如 "线代/特征值.md"
	 */
	public static Optional<Path> resolve(Path vaultRoot, String noteRelativePath, String src) {
		try {
			if (src == null || src.isBlank()) {
				return Optional.empty();
			}
			try {
				// 图片路径中的加号是文件名的一部分，不是表单编码的空格。
				src = URLDecoder.decode(src.replace("+", "%2B"), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException ignored) {
				// 不完整的百分号编码按原始文件名查找。
			}
			src = src.replace('\\', '/');
			if (src.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || src.startsWith("/")) {
				return Optional.empty();
			}
			Path relative = Path.of(src);
			if (relative.isAbsolute()) {
				return Optional.empty();
			}
			String filename = relative.getFileName().toString();
			int dot = filename.lastIndexOf('.');
			String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
			if (dot < 0 || !Set.of("png", "jpg", "jpeg", "gif", "bmp").contains(extension)) {
				return Optional.empty();
			}
			Path root = vaultRoot.toAbsolutePath().normalize();
			Path realRoot = root.toRealPath();
			Path note = root.resolve(noteRelativePath).normalize();
			if (!note.startsWith(root) || note.equals(root)) {
				return Optional.empty();
			}
			Path besideNote = note.getParent().resolve(relative).normalize();
			if (!besideNote.startsWith(root)) {
				return Optional.empty();
			}
			if (isImageFile(besideNote, root, realRoot)) {
				return Optional.of(besideNote);
			}
			Path atRoot = root.resolve(relative).normalize();
			if (isImageFile(atRoot, root, realRoot)) {
				return Optional.of(atRoot);
			}
			if (src.contains("/")) {
				return Optional.empty();
			}
			var matches = new ArrayList<Path>();
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					return !dir.equals(root) && dir.getFileName().toString().startsWith(".")
							? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.getFileName().toString().equals(filename) && isImageFile(file, root, realRoot)) {
						matches.add(file);
					}
					return FileVisitResult.CONTINUE;
				}
			});
			return matches.stream().min(Comparator.comparing(
					path -> root.relativize(path).toString().replace('\\', '/')));
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	private static boolean isImageFile(Path file, Path root, Path realRoot) throws IOException {
		return file.startsWith(root) && Files.isRegularFile(file) && file.toRealPath().startsWith(realRoot);
	}

	/**
	 * 解码图片文件（javax.imageio），等比缩小到宽高都不超过 maxDim（小图不放大），返回 ARGB 位图。
	 * 透明通道保留；没有透明通道的图 alpha = 0xFF。gif 只取第一帧。解码失败 → empty，不抛异常。
	 */
	public static Optional<Raster> load(Path file, int maxDim) {
		try {
			if (maxDim <= 0) {
				return Optional.empty();
			}
			BufferedImage image;
			try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
				// 大照片先隔行隔列抽样读进来（解码出来最长边不超过 4 倍 maxDim），再精细缩小：不会为一张巨图整张分配内存
				image = in == null ? null : decode(in, Integer.MAX_VALUE, Math.max(1, maxDim) * 4);
			}
			if (image == null) {
				return Optional.empty();
			}
			int width = image.getWidth();
			int height = image.getHeight();
			if (width > maxDim || height > maxDim) {
				double scale = (double) maxDim / Math.max(width, height);
				width = Math.max(1, (int) Math.round(width * scale));
				height = Math.max(1, (int) Math.round(height * scale));
				var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				var graphics = scaled.createGraphics();
				try {
					graphics.setComposite(AlphaComposite.Src);
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
							RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					graphics.drawImage(image, 0, 0, width, height, null);
				} finally {
					graphics.dispose();
				}
				image = scaled;
			}
			return Optional.of(toRaster(image));
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	/** 位图 → PNG 字节。 */
	public static byte[] toPng(Raster raster) {
		var image = new BufferedImage(raster.width(), raster.height(), BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, raster.width(), raster.height(), raster.argb(), 0, raster.width());
		try (var output = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, "png", output)) {
				throw new IOException("PNG 编码器不可用");
			}
			return output.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("PNG 编码失败", e);
		}
	}

	/** 网络收到的图最多这么大（服务端发的都缩到 1024 以内，这里留余量）。 */
	public static final int MAX_RECEIVED_SIDE = 4096;
	public static final long MAX_RECEIVED_PIXELS = 16L * 1024 * 1024;

	/**
	 * PNG（或 jpg 等 ImageIO 能读的）字节 → 位图；失败 → empty，不抛异常。
	 * 字节来自网络（服务端可能不怀好意）：先只读文件头里的宽高，超过 MAX_RECEIVED_SIDE / MAX_RECEIVED_PIXELS 直接拒绝，
	 * 免得一个高度压缩的"巨图"包在解码时把内存撑爆（OutOfMemoryError 是接不住的）。
	 */
	public static Optional<Raster> fromBytes(byte[] data) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			BufferedImage image = in == null ? null : decode(in, MAX_RECEIVED_PIXELS, MAX_RECEIVED_SIDE);
			return image == null ? Optional.empty() : Optional.of(toRaster(image));
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	/**
	 * 解码第一帧，解码前先看宽高：总像素超过 maxPixels → null（拒绝）；
	 * 最长边超过 maxSide → 隔行隔列抽样读，让读出来的最长边不超过 maxSide。
	 */
	private static BufferedImage decode(ImageInputStream in, long maxPixels, int maxSide) throws IOException {
		var readers = ImageIO.getImageReaders(in);
		if (!readers.hasNext()) {
			return null;
		}
		ImageReader reader = readers.next();
		try {
			reader.setInput(in, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if (width <= 0 || height <= 0 || (long) width * height > maxPixels) {
				return null;
			}
			ImageReadParam param = reader.getDefaultReadParam();
			int step = (Math.max(width, height) + maxSide - 1) / maxSide;
			if (step > 1) {
				param.setSourceSubsampling(step, step, 0, 0);
			}
			return reader.read(0, param);
		} finally {
			reader.dispose();
		}
	}

	private static Raster toRaster(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		return new Raster(width, height, image.getRGB(0, 0, width, height, null, 0, width));
	}
}
