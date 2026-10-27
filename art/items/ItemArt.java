import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 两张物品栏贴图（16×16，逐格填色，不用渐变、不做抗锯齿），照原版成书的样子画：
 * - memory_note：记忆笔记。等距视角的一本书，封面换成青金石蓝（配方里用了青金石），斜纹压花保留，
 *   封面正中一枚金色菱形徽记
 * - locked_note：上锁笔记。同样的蓝封面，徽记换成带锁孔的铁片，书页右侧多一道铁扣（配方里用了铁锭、绊线钩）
 *
 *   java art/items/ItemArt.java
 *
 * 输出：src/main/resources/assets/memorypalace/textures/item/*.png（原尺寸，模组用），
 *       art/items/*-preview.png（最近邻放大 16 倍，看图用）。
 */
public final class ItemArt {
	static final Path TEXTURES = Path.of("src", "main", "resources", "assets", "memorypalace", "textures", "item");
	static final Path PREVIEW = Path.of("art", "items");

	// 书的轮廓：和原版成书同一个造型。字符含义见下面的调色板
	//   O 封面外轮廓  1 封面暗  2 封面中  3 封面亮（主色）  E 封面边缘暗  s 压花斜纹
	//   p 书页亮  q 书页中  r 书页暗  w 书页高光  f 书页阴影  k 底边轮廓
	static final String[] BOOK = {
			"................",
			"........OOO.....",
			"......OO123E....",
			"....OO123323E...",
			"..OO1333ss323E..",
			"OO133s333ss323E.",
			"E13233s333ss32OE",
			"EO13233s3332OOf.",
			"EOq1323331OOrqr.",
			"k1wq1321OOrqqrOE",
			".k1wq1OOrqqrOOkk",
			"..k1wqrqqrOEkk..",
			"...k1wqrOEkk....",
			"....k1OEkk......",
			".....kkk........",
			"................",
	};

	// 青金石蓝封面 + 原版书页的灰
	static final Map<Character, Integer> PALETTE = Map.ofEntries(
			Map.entry('O', 0x0A1A45), Map.entry('1', 0x1A3D8F), Map.entry('2', 0x1C53A8), Map.entry('3', 0x345EC3),
			Map.entry('E', 0x102C6E), Map.entry('s', 0x24489E),
			Map.entry('p', 0xB7B7B7), Map.entry('q', 0xB7B7B7), Map.entry('r', 0x999999), Map.entry('w', 0xD6D6D6),
			Map.entry('f', 0x5B5B5B), Map.entry('k', 0x161005),
			// 金（原版金锭的颜色）
			Map.entry('Y', 0xFDF55F), Map.entry('G', 0xE9B115), Map.entry('R', 0xB26411),
			// 铁（原版铁锭的颜色）+ 锁孔
			Map.entry('A', 0xEDEDED), Map.entry('B', 0xB2B2B2), Map.entry('C', 0x7E7E7E), Map.entry('K', 0x1A1220));

	public static void main(String[] args) throws IOException {
		check(BOOK);
		// 记忆笔记：封面正中的金色菱形徽记（上亮、左右金、下暗、中心最亮）
		String[] memory = paint(BOOK, new int[][]{{5, 8, 'Y'}, {6, 7, 'G'}, {6, 8, 'Y'}, {6, 9, 'G'}, {7, 8, 'R'}});
		// 上锁笔记：同一位置换成大一圈的铁片（左上亮、右下暗），中间两格高的锁孔；书页右侧一道铁扣从封面压到书页上
		String[] locked = paint(BOOK, new int[][]{
				{5, 8, 'A'}, {5, 9, 'B'},
				{6, 7, 'A'}, {6, 8, 'K'}, {6, 9, 'B'}, {6, 10, 'C'},
				{7, 7, 'B'}, {7, 8, 'K'}, {7, 9, 'C'},
				{8, 8, 'C'},
				{7, 12, 'A'}, {8, 12, 'B'}, {8, 13, 'A'}, {9, 13, 'C'}, {9, 14, 'B'}});
		write("memory_note", memory);
		write("locked_note", locked);
	}

	static String[] paint(String[] base, int[][] pixels) {
		char[][] rows = new char[base.length][];
		for (int y = 0; y < base.length; y++) {
			rows[y] = base[y].toCharArray();
		}
		for (int[] p : pixels) {
			if (rows[p[0]][p[1]] == '.') {
				throw new IllegalStateException("画到书外面了：" + p[0] + "," + p[1]);
			}
			rows[p[0]][p[1]] = (char) p[2];
		}
		String[] out = new String[rows.length];
		for (int y = 0; y < rows.length; y++) {
			out[y] = new String(rows[y]);
		}
		return out;
	}

	static void check(String[] grid) {
		if (grid.length != 16) {
			throw new IllegalStateException("要 16 行");
		}
		for (int y = 0; y < 16; y++) {
			if (grid[y].length() != 16) {
				throw new IllegalStateException("第 " + y + " 行不是 16 格：" + grid[y].length());
			}
		}
	}

	static void write(String name, String[] grid) throws IOException {
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				char c = grid[y].charAt(x);
				if (c != '.') {
					Integer rgb = PALETTE.get(c);
					if (rgb == null) {
						throw new IllegalStateException("调色板里没有 " + c);
					}
					img.setRGB(x, y, 0xFF000000 | rgb);
				}
			}
		}
		Files.createDirectories(TEXTURES);
		ImageIO.write(img, "png", TEXTURES.resolve(name + ".png").toFile());
		int scale = 16;
		BufferedImage big = new BufferedImage(16 * scale, 16 * scale, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 16 * scale; y++) {
			for (int x = 0; x < 16 * scale; x++) {
				big.setRGB(x, y, img.getRGB(x / scale, y / scale));
			}
		}
		ImageIO.write(big, "png", PREVIEW.resolve(name + "-preview.png").toFile());
	}
}
