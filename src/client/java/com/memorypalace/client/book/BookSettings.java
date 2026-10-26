package com.memorypalace.client.book;

import com.memorypalace.MemoryPalace;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 书本界面的个人设置，存在 .minecraft/config/memorypalace.properties。
 */
public final class BookSettings {
	/**
	 * 书页大小：书页本身变大，字号不变，所以一页能放更多行。
	 * fill = 占屏幕可用空间的比例；0 表示固定为原版大小。
	 */
	public enum PageSize {
		VANILLA(0.0F), MEDIUM(0.7F), LARGE(0.85F), FIT(1.0F);

		public final float fill;

		PageSize(float fill) {
			this.fill = fill;
		}
	}

	/** 字体大小：只放大文字，书页大小不变。 */
	public enum FontSize {
		NORMAL(1.0F), LARGE(1.5F), XLARGE(2.0F);

		public final float scale;

		FontSize(float scale) {
			this.scale = scale;
		}
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("memorypalace.properties");
	private static PageSize pageSize = PageSize.FIT;
	private static FontSize fontSize = FontSize.NORMAL;
	private static boolean loaded;

	private BookSettings() {}

	public static PageSize pageSize() {
		load();
		return pageSize;
	}

	public static FontSize fontSize() {
		load();
		return fontSize;
	}

	public static void setPageSize(PageSize value) {
		pageSize = value;
		save();
	}

	public static void setFontSize(FontSize value) {
		fontSize = value;
		save();
	}

	/** 按钮上显示的名字，例如 "铺满屏幕"。 */
	public static Component label(Enum<?> value) {
		return Component.translatable("settings.memorypalace." + value.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT)
				+ "." + value.name().toLowerCase(Locale.ROOT));
	}

	public static <E extends Enum<E>> E next(E value) {
		E[] all = value.getDeclaringClass().getEnumConstants();
		return all[(value.ordinal() + 1) % all.length];
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!Files.exists(FILE)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			p.load(r);
			pageSize = parse(PageSize.class, p.getProperty("page_size"), PageSize.FIT);
			fontSize = parse(FontSize.class, p.getProperty("font_size"), FontSize.NORMAL);
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("读取设置失败，使用默认值", e);
		}
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		try {
			return value == null ? fallback : Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static void save() {
		Properties p = new Properties();
		p.setProperty("page_size", pageSize.name());
		p.setProperty("font_size", fontSize.name());
		try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
			p.store(w, "Memory Palace settings");
		} catch (IOException e) {
			MemoryPalace.LOGGER.warn("保存设置失败", e);
		}
	}
}
