import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组图标「书中宫殿」：64×64 逐格填色，不用渐变、不做抗锯齿。
 * 画面是一个原版风的等距小场景：雕纹书架方块上摊着一本书，书页上立着一座方块搭的金宫殿，
 * 书架上空着的一格发光，射出一道导航光柱。三个朝向的面亮度不同（顶 > 左 > 右），每个面都带逐格杂色纹理。
 *
 *   java art/icon/IconArt.java draw    按下面的场景生成 icon.grid.txt，再出图
 *   java art/icon/IconArt.java render  只按 icon.grid.txt 出图（手改网格后用这个）
 *
 * icon.grid.txt 开头是调色板（每行 "字符 #RRGGBB"），空一行后是 64 行 × 64 格。
 * 输出：art/icon/icon-64.png（原尺寸）、icon-512.png（最近邻放大 8 倍）。
 */
public final class IconArt {
	static final int N = 64;
	static final Path DIR = Path.of("art", "icon");
	static final Path GRID = DIR.resolve("icon.grid.txt");

	// ---- 颜色 ----
	static final int OUTLINE = 0x1A1220;
	static final int SKY_TOP = 0x10162B, SKY_MID = 0x18223F, SKY_LOW = 0x22305A, HALO = 0x2E3F70;
	static final int STAR = 0xE8F0FF;
	static final int[] OAK = {0xC29D62, 0xAF8A52, 0x9C7943}; // 橡木：亮、中、暗
	static final int OAK_SEAM = 0x6A4E2C, SLOT = 0x2A1C10;
	static final int[] GOLD = {0xFFF1A6, 0xF4CE5A, 0xE0B23E}; // 金块的杂色
	static final int GOLD_EDGE = 0xB8862E, DOOR = 0x1C1426;
	static final int PAGE = 0xF2E6C8, PAGE_SHADE = 0xDCCBA3, INK = 0x4A4250;
	static final int COVER = 0xB23A3A, COVER_DARK = 0x7E2626;
	static final int GLOW = 0xFFE27A, GLOW_HOT = 0xFFF6CC, BEAM = 0x5FE3E0, BEAM_CORE = 0xC8FFFB;
	static final int[] SPINES = {0xB23A3A, 0x3A6FB2, 0x3F8F4A, 0x7A4AA8, 0xD9A53A, 0x2F7E86};

	/** 三个朝向的面的亮度：顶、左（+Y 面）、右（+X 面）。 */
	static final double TOP = 1.0, LEFT = 0.84, RIGHT = 0.64;

	// ---- 等距投影：世界坐标 X 往右下、Y 往左下、Z 往上；1 个单位 = 一格材质 ----
	static final double K = 1.375; // 每单位多少像素（书架方块 16 单位宽 → 屏幕上半宽 22 像素）
	static final double CX = 32, CY = 40;

	enum Face { TOP, LEFT, RIGHT }

	interface Texture {
		/** u、v 是这个面上的世界坐标（顶面：u=X, v=Y；左面：u=X, v=Z；右面：u=Y, v=Z）。 */
		int color(Face face, double u, double v);
	}

	record Box(double x0, double y0, double z0, double x1, double y1, double z1, Texture tex) {}

	static final List<Box> boxes = new ArrayList<>();
	static final int[][] px = new int[N][N];
	static final boolean[][] obj = new boolean[N][N];
	// 每个像素是哪个方块画的（boxes 里的下标，-1 = 背景）；前角那座塔的方块从 frontTower 开始
	static final int[][] owner = new int[N][N];
	static int frontTower = Integer.MAX_VALUE;

	public static void main(String[] args) throws IOException {
		if (args.length == 1 && args[0].equals("draw")) {
			draw();
			writeGrid();
		} else if (!(args.length == 1 && args[0].equals("render"))) {
			System.err.println("用法：java art/icon/IconArt.java draw|render");
			System.exit(2);
		}
		render();
	}

	// ---- 场景 ----

	static void draw() {
		sky();
		scene();
		rasterize();
		separateFrontTower();
		shadowOnShelfTop();
		outline();
		beam();
		stars();
	}

	static void scene() {
		// 雕纹书架方块
		boxes.add(new Box(0, 0, 0, 16, 16, 16, IconArt::shelf));
		// 摊开的书：红书皮 + 左右两叠书页，外侧一级比内侧高，中缝露出书皮
		boxes.add(new Box(0.5, 3, 16, 15.5, 13, 16.6, solid(COVER, COVER_DARK)));
		pageBlock(1, 7.6, true);
		pageBlock(8.4, 15, false);
		// 宫殿：立在书页中间，全是方块
		double z = Z0;
		Texture gold = IconArt::gold;
		boxes.add(new Box(4.5, 4.5, z, 11.5, 11.5, z + 7, IconArt::hall));    // 主殿
		boxes.add(new Box(4, 4, z + 7, 12, 12, z + 8, IconArt::trim));         // 檐
		boxes.add(new Box(5.5, 5.5, z + 8, 10.5, 10.5, z + 10, gold));         // 第二级屋顶
		boxes.add(new Box(6.75, 6.75, z + 10, 9.25, 9.25, z + 12, gold));      // 第三级
		boxes.add(new Box(7.5, 7.5, z + 12, 8.5, 8.5, z + 15, gold));          // 顶尖
		// 四座角塔：主殿四个角各一座
		for (double[] c : new double[][]{{3, 3}, {10.5, 3}, {3, 10.5}, {10.5, 10.5}}) {
			double x = c[0], y = c[1];
			if (x > 10 && y > 10) {
				frontTower = boxes.size(); // 前角这座挡在主殿正前方，要和后面描一道分界（见 separateFrontTower）
			}
			boxes.add(new Box(x, y, z, x + 2.5, y + 2.5, z + 11, IconArt::tower));
			boxes.add(new Box(x - 0.25, y - 0.25, z + 11, x + 2.75, y + 2.75, z + 12, IconArt::trim));
			boxes.add(new Box(x + 0.75, y + 0.75, z + 12, x + 1.75, y + 1.75, z + 14, gold));
		}
	}

	/** 宫殿立在内侧那一级书页上。 */
	static final double Z0 = 18.6;

	static void pageBlock(double x0, double x1, boolean leftPage) {
		double inner = leftPage ? x1 - 2.6 : x0 + 2.6;
		// 厚厚一叠书页：外侧一级比内侧高，侧面看得到一层层的页边
		if (leftPage) {
			boxes.add(new Box(x0, 3.5, 16.6, inner, 12.5, 19.4, IconArt::page));
			boxes.add(new Box(inner, 3.5, 16.6, x1, 12.5, Z0, IconArt::page));
		} else {
			boxes.add(new Box(x0, 3.5, 16.6, inner, 12.5, Z0, IconArt::page));
			boxes.add(new Box(inner, 3.5, 16.6, x1, 12.5, 19.4, IconArt::page));
		}
	}

	// ---- 材质 ----

	/** 雕纹书架：左面是两行三格书，右面是木板侧面，顶面是带边框的木板。 */
	static int shelf(Face face, double u, double v) {
		int tu = (int) Math.floor(u), tv = (int) Math.floor(v);
		tu = Math.clamp(tu, 0, 15);
		tv = Math.clamp(tv, 0, 15);
		return switch (face) {
			case TOP -> {
				if (tu == 0 || tu == 15 || tv == 0 || tv == 15) yield shade(OAK[2], 0.85);
				if (tv % 4 == 3) yield OAK_SEAM;
				yield noisy(OAK, tu, tv, 1);
			}
			case RIGHT -> {
				int row = 15 - tv;
				if (row % 4 == 3) yield OAK_SEAM;
				int seam = (row / 4) % 2 == 0 ? 5 : 11;
				if (tu == seam) yield OAK_SEAM;
				yield noisy(OAK, tu, row, 2);
			}
			case LEFT -> bookFace(tu, 15 - tv);
		};
	}

	/** 雕纹书架正面（行 0 在上）：边框 1 格，中间横隔 2 格，三列各 4 格宽、6 格高。 */
	static int bookFace(int col, int row) {
		int slotCol = col >= 1 && col <= 4 ? 0 : col >= 6 && col <= 9 ? 1 : col >= 11 && col <= 14 ? 2 : -1;
		int slotRow = row >= 1 && row <= 6 ? 0 : row >= 9 && row <= 14 ? 1 : -1;
		if (slotCol < 0 || slotRow < 0) {
			return row == 8 || row == 15 ? shade(OAK[2], 0.9) : noisy(OAK, col, row, 3); // 隔板、底边下沿略暗
		}
		int inCol = col - (slotCol == 0 ? 1 : slotCol == 1 ? 6 : 11);
		int inRow = row - (slotRow == 0 ? 1 : 9);
		if (slotCol == 0 && slotRow == 0) {
			// 空着的那一格：书被取走了，里面发光
			return inCol >= 1 && inCol <= 2 && inRow >= 2 && inRow <= 4 ? GLOW_HOT : GLOW;
		}
		if (inRow == 0) {
			return SLOT; // 格子上沿的阴影
		}
		int book = slotRow * 12 + slotCol * 4 + inCol;
		int height = 4 + (int) (hash(book, 7, 9) * 2.99); // 书高 4~6 格，参差不齐
		if (inRow < 6 - height + 1) {
			return SLOT;
		}
		int spine = SPINES[(int) (hash(book, 3, 5) * SPINES.length)];
		if (inRow == 6 - height + 1) {
			return shade(spine, 1.25); // 书顶亮一点
		}
		return inRow == 3 ? shade(spine, 0.75) : spine; // 书脊上一道暗箍
	}

	static int page(Face face, double u, double v) {
		if (face == Face.TOP) {
			// 宫殿两侧露出来的书页上有几行字
			boolean margin = u < 2.9 || u > 13.6;
			if (margin && Math.floor(v) % 2 == 0 && v > 4.5 && v < 11.5 && hash((int) v, (int) (u * 2), 11) > 0.2) {
				return INK;
			}
			return PAGE;
		}
		// 侧面：一层层的书页边
		return (int) Math.floor(v * 3) % 2 == 0 ? PAGE : PAGE_SHADE;
	}

	/** 金块：一格一个杂色，亮暗点不多，免得糊成一片。 */
	static int gold(Face face, double u, double v) {
		double r = hash((int) Math.floor(u * 1.2), (int) Math.floor(v * 1.2), face.ordinal() + 20);
		return r < 0.12 ? GOLD[0] : r < 0.85 ? GOLD[1] : GOLD[2];
	}

	/** 檐口、塔帽：比墙面暗一档，把一层层分开。 */
	static int trim(Face face, double u, double v) {
		return face == Face.TOP ? gold(face, u, v) : GOLD_EDGE;
	}

	/** 主殿：左面（正面）开一扇大门，右面两扇小窗。 */
	static int hall(Face face, double u, double v) {
		if (face == Face.LEFT) {
			if (u > 5.5 && u < 8.3 && v < Z0 + 4.5) return DOOR; // 偏左一点：右边被前角的塔挡着
			if (u > 5 && u < 8.8 && v >= Z0 + 4.5 && v < Z0 + 5.3) return GOLD_EDGE; // 门楣
		} else if (face == Face.RIGHT) {
			boolean window = (u > 5.6 && u < 6.9 || u > 9.1 && u < 10.4) && v > Z0 + 2.5 && v < Z0 + 4.8;
			if (window) return GOLD_EDGE; // 窗用暗金不用黑，缩小后才不会和门凑成一张脸
		}
		return gold(face, u, v);
	}

	/** 塔：中间一道窄窗。 */
	static int tower(Face face, double u, double v) {
		if (face != Face.TOP) {
			if (Math.abs(u - (u < 8 ? 4.25 : 11.75)) < 0.5 && v > Z0 + 6 && v < Z0 + 8.5) return GOLD_EDGE;
		}
		return gold(face, u, v);
	}

	static Texture solid(int top, int side) {
		return (face, u, v) -> face == Face.TOP ? top : side;
	}

	// ---- 光栅化：逐像素找最靠前的面 ----

	static void rasterize() {
		double[][] depth = new double[N][N];
		for (double[] row : depth) java.util.Arrays.fill(row, Double.NEGATIVE_INFINITY);
		for (int[] row : owner) java.util.Arrays.fill(row, -1);
		for (int i = 0; i < boxes.size(); i++) {
			Box b = boxes.get(i);
			current = i;
			for (int y = 0; y < N; y++) {
				for (int x = 0; x < N; x++) {
					double a = (x + 0.5 - CX) / K;           // X - Y
					double sy = (y + 0.5 - CY) / K;
					// 顶面 Z = z1
					double sum = 2 * (sy + b.z1);            // X + Y
					double X = (sum + a) / 2, Y = (sum - a) / 2;
					if (in(X, b.x0, b.x1) && in(Y, b.y0, b.y1)) {
						plot(depth, x, y, X + Y + b.z1, shade(b.tex.color(Face.TOP, X, Y), TOP));
					}
					// 左面 Y = y1
					X = a + b.y1;
					double Z = (X + b.y1) / 2 - sy;
					if (in(X, b.x0, b.x1) && in(Z, b.z0, b.z1)) {
						plot(depth, x, y, X + b.y1 + Z, shade(b.tex.color(Face.LEFT, X, Z), LEFT));
					}
					// 右面 X = x1
					Y = b.x1 - a;
					Z = (b.x1 + Y) / 2 - sy;
					if (in(Y, b.y0, b.y1) && in(Z, b.z0, b.z1)) {
						plot(depth, x, y, b.x1 + Y + Z, shade(b.tex.color(Face.RIGHT, Y, Z), RIGHT));
					}
				}
			}
		}
	}

	static boolean in(double value, double lo, double hi) {
		return value >= lo && value < hi;
	}

	static int current; // rasterize 正在画的方块

	static void plot(double[][] depth, int x, int y, double d, int rgb) {
		if (d > depth[y][x]) {
			depth[y][x] = d;
			px[y][x] = rgb;
			obj[y][x] = true;
			owner[y][x] = current;
		}
	}

	/**
	 * 前角的塔和后面的主殿都是金色，挨在一起看不出是一座单独的塔：
	 * 塔外紧挨着它的那一圈主殿像素压成深金色，当作分界线。
	 */
	static void separateFrontTower() {
		boolean[][] line = new boolean[N][N];
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (owner[y][x] >= 0 && owner[y][x] < frontTower
						&& (isFront(x - 1, y) || isFront(x + 1, y) || isFront(x, y - 1))) {
					line[y][x] = true;
				}
			}
		}
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (line[y][x]) px[y][x] = SEPARATOR;
			}
		}
	}

	static final int SEPARATOR = 0x7A5418;

	static boolean isFront(int x, int y) {
		return x >= 0 && y >= 0 && x < N && y < N && owner[y][x] >= frontTower;
	}

	/** 书在书架顶面上投下的影子：顶面上靠近书的一圈压暗。 */
	static void shadowOnShelfTop() {
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				double a = (x + 0.5 - CX) / K, sy = (y + 0.5 - CY) / K;
				double sum = 2 * (sy + 16);
				double X = (sum + a) / 2, Y = (sum - a) / 2;
				boolean onTop = in(X, 0, 16) && in(Y, 0, 16);
				boolean nearBook = X > 0 && X < 16.3 && Y > 2.4 && Y < 13.8;
				if (onTop && nearBook && isTopOfShelf(x, y)) {
					px[y][x] = shade(px[y][x], 0.7);
				}
			}
		}
	}

	/** 这个像素此刻显示的是不是书架顶面（没被书挡住）。 */
	static boolean isTopOfShelf(int x, int y) {
		int c = px[y][x];
		for (int o : OAK) {
			if (c == shade(o, TOP)) return true;
		}
		return c == OAK_SEAM || c == shade(shade(OAK[2], 0.85), TOP);
	}

	// ---- 背景与收尾 ----

	/** 夜空：上深下浅四条横向色带（不用圆形光晕，原版里没有圆的东西），交界用棋盘格抖动。 */
	static void sky() {
		int[] bands = {SKY_TOP, SKY_MID, SKY_LOW, HALO};
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				double band = y / 16.0 + ((x + y) % 2 == 0 ? 0.06 : -0.06);
				px[y][x] = bands[Math.clamp((int) band, 0, bands.length - 1)];
			}
		}
	}

	static void outline() {
		boolean[][] edge = new boolean[N][N];
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (!obj[y][x] && (isObj(x - 1, y) || isObj(x + 1, y) || isObj(x, y - 1) || isObj(x, y + 1))) {
					edge[y][x] = true;
				}
			}
		}
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (edge[y][x]) px[y][x] = OUTLINE;
			}
		}
	}

	/** 导航光柱：从发光的空格往上直到画面顶端，中间一道亮芯，顶端渐淡。 */
	static void beam() {
		// 光柱对准发光格里最亮那块的中间
		int sum = 0, count = 0, bottom = 0;
		for (int y = 0; y < N; y++) {
			for (int x = 0; x < N; x++) {
				if (px[y][x] == shade(GLOW_HOT, LEFT)) {
					sum += x;
					count++;
					bottom = Math.max(bottom, y);
				}
			}
		}
		int x = Math.round((float) sum / count);
		for (int y = 6; y <= bottom; y++) {
			if (px[y][x] == shade(GLOW_HOT, LEFT) || px[y][x] == shade(GLOW, LEFT)) continue;
			px[y][x] = BEAM_CORE;
			px[y][x - 1] = BEAM;
			px[y][x + 1] = BEAM;
		}
		px[5][x] = BEAM;
		px[4][x - 1] = BEAM;
		px[2][x] = BEAM;
	}

	static void stars() {
		int[][] at = {{6, 9}, {53, 5}, {58, 22}, {4, 30}, {27, 2}, {60, 40}};
		for (int[] s : at) {
			if (!obj[s[1]][s[0]]) px[s[1]][s[0]] = STAR;
		}
		int sx = 49, sy = 10; // 一颗十字大星
		px[sy][sx] = STAR;
		px[sy - 1][sx] = HALO;
		px[sy + 1][sx] = HALO;
		px[sy][sx - 1] = HALO;
		px[sy][sx + 1] = HALO;
	}

	// ---- 工具 ----

	static boolean isObj(int x, int y) {
		return x >= 0 && y >= 0 && x < N && y < N && obj[y][x];
	}

	/** 稳定的伪随机：同一格每次都一样。 */
	static double hash(int a, int b, int seed) {
		long h = a * 73856093L ^ b * 19349663L ^ seed * 83492791L;
		h = (h ^ (h >>> 13)) * 0x5bd1e995L;
		h ^= h >>> 15;
		return (h & 0xFFFF) / 65536.0;
	}

	/** 三档杂色：大多是中间色，少量亮、暗。 */
	static int noisy(int[] shades, int u, int v, int seed) {
		double r = hash(u, v, seed);
		return r < 0.2 ? shades[0] : r < 0.8 ? shades[1] : shades[2];
	}

	static int shade(int rgb, double f) {
		int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
		int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
		int b = (int) Math.min(255, Math.round((rgb & 0xFF) * f));
		return r << 16 | g << 8 | b;
	}

	// ---- 网格文件与出图 ----

	static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*+=?!<>~^";

	static void writeGrid() throws IOException {
		Map<Integer, Character> keys = new LinkedHashMap<>();
		StringBuilder rows = new StringBuilder();
		for (int[] row : px) {
			for (int rgb : row) {
				Character c = keys.get(rgb);
				if (c == null) {
					if (keys.size() == CHARS.length()) throw new IllegalStateException("颜色太多");
					c = CHARS.charAt(keys.size());
					keys.put(rgb, c);
				}
				rows.append(c);
			}
			rows.append('\n');
		}
		StringBuilder out = new StringBuilder();
		keys.forEach((rgb, c) -> out.append(c).append(" #").append(String.format("%06X", rgb)).append('\n'));
		out.append('\n').append(rows);
		Files.writeString(GRID, out, StandardCharsets.UTF_8);
	}

	static void render() throws IOException {
		List<String> lines = Files.readAllLines(GRID, StandardCharsets.UTF_8);
		Map<Character, Integer> palette = new HashMap<>();
		int i = 0;
		for (; i < lines.size() && !lines.get(i).isEmpty(); i++) {
			String l = lines.get(i);
			palette.put(l.charAt(0), Integer.parseInt(l.substring(3), 16));
		}
		List<String> rows = lines.subList(i + 1, lines.size());
		if (rows.size() != N) throw new IllegalStateException("网格应为 " + N + " 行，实际 " + rows.size());
		for (int scale : new int[]{1, 8}) {
			BufferedImage img = new BufferedImage(N * scale, N * scale, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < N; y++) {
				String row = rows.get(y);
				if (row.length() != N) throw new IllegalStateException("第 " + (y + 1) + " 行应为 " + N + " 格");
				for (int x = 0; x < N; x++) {
					Integer rgb = palette.get(row.charAt(x));
					if (rgb == null) throw new IllegalStateException("第 " + (y + 1) + " 行第 " + (x + 1) + " 格：调色板里没有 " + row.charAt(x));
					for (int dy = 0; dy < scale; dy++) {
						for (int dx = 0; dx < scale; dx++) img.setRGB(x * scale + dx, y * scale + dy, rgb);
					}
				}
			}
			ImageIO.write(img, "png", DIR.resolve("icon-" + (N * scale) + ".png").toFile());
		}
	}
}
