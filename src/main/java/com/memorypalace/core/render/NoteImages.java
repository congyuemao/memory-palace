package com.memorypalace.core.render;

import java.nio.file.Path;
import java.util.Optional;

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
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * 解码图片文件（javax.imageio），等比缩小到宽高都不超过 maxDim（小图不放大），返回 ARGB 位图。
	 * 透明通道保留；没有透明通道的图 alpha = 0xFF。gif 只取第一帧。解码失败 → empty，不抛异常。
	 */
	public static Optional<Raster> load(Path file, int maxDim) {
		throw new UnsupportedOperationException("TODO");
	}

	/** 位图 → PNG 字节。 */
	public static byte[] toPng(Raster raster) {
		throw new UnsupportedOperationException("TODO");
	}

	/** PNG（或 jpg 等 ImageIO 能读的）字节 → 位图；失败 → empty，不抛异常。 */
	public static Optional<Raster> fromBytes(byte[] data) {
		throw new UnsupportedOperationException("TODO");
	}
}
