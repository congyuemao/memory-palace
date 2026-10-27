package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 记录"每篇笔记在世界里被看到过的位置"。一篇笔记可以有多个位置（书可以复制、挂在好几处）。
 * 只在玩家从书架/讲台打开笔记时自动记录；用的时候由调用方检查位置是否还有效，无效就 remove。
 * 存在 &lt;笔记库&gt;/.palace/locations.tsv（隐藏文件夹，不会被当成笔记扫描）。
 * 线程安全：公开方法可以从多个线程调用。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteLocations {
	private final Path file;
	private List<Entry> entries = new ArrayList<>();

	private record Entry(UUID noteId, Location location) {}

	/** Location.slot 用这个值表示讲台。 */
	public static final int LECTERN_SLOT = -1;

	/**
	 * 一个位置。
	 * @param dimension 维度 ID，例如 "minecraft:overworld"（不能含制表符或换行）
	 * @param slot      雕纹书架的格子 0~5；讲台为 -1
	 * @param seenAt    最后一次在这里看到的时间（毫秒时间戳）
	 */
	public record Location(String dimension, int x, int y, int z, int slot, long seenAt) {
		/** 同一个地方（忽略时间）。 */
		public boolean samePlace(Location other) {
			return dimension.equals(other.dimension) && x == other.x && y == other.y && z == other.z && slot == other.slot;
		}
	}

	/**
	 * file 不存在时视为空。文件里格式不对的行跳过，不抛异常。
	 */
	public NoteLocations(Path file) throws IOException {
		this.file = file.toAbsolutePath().normalize();
		try (var reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] fields = line.split("\t", -1);
				if (fields.length != 7) {
					continue;
				}
				try {
					UUID noteId = UUID.fromString(fields[0]);
					if (!noteId.toString().equalsIgnoreCase(fields[0])) {
						continue;
					}
					Location location = new Location(fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
							Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), Long.parseLong(fields[6]));
					validate(location);
					entries.removeIf(entry -> entry.location().samePlace(location));
					entries.add(new Entry(noteId, location));
				} catch (IllegalArgumentException e) {
					// 损坏的行不影响其他位置记录。
				}
			}
		} catch (NoSuchFileException e) {
			// 首次使用时文件尚未创建。
		}
	}

	private static final long RECORD_AGAIN_AFTER_MS = 60_000;

	/**
	 * 记录"在 location 看到了笔记 noteId"。同一个地方已有记录就更新时间，不重复添加
	 * （同一篇、同一个地方一分钟内再记，什么都不做：时间只是给"5 分钟前"这种显示用，不必每点一下就重写文件）。
	 * 同一个地方（同维度同坐标同格子）只能属于一篇笔记：如果之前记录的是别的笔记，把那条去掉。
	 * 立刻保存到文件（原子写：临时文件 + move）。
	 */
	public synchronized void record(UUID noteId, Location location) throws IOException {
		Objects.requireNonNull(noteId);
		validate(location);
		// 同一篇在同一个位置、一分钟内刚记过：只是又点了一下，不用整个文件重写一遍
		boolean fresh = entries.stream().anyMatch(entry -> entry.noteId().equals(noteId)
				&& entry.location().samePlace(location)
				&& Math.abs(location.seenAt() - entry.location().seenAt()) < RECORD_AGAIN_AFTER_MS);
		if (fresh) {
			return;
		}
		List<Entry> updated = new ArrayList<>(entries);
		updated.removeIf(entry -> entry.location().samePlace(location));
		updated.add(new Entry(noteId, location));
		save(updated);
		entries = updated;
	}

	/** 这篇笔记的所有位置，最近看到的排在前面。没有记录时为空列表。 */
	public synchronized List<Location> get(UUID noteId) {
		return entries.stream().filter(entry -> entry.noteId().equals(noteId)).map(Entry::location)
				.sorted(Comparator.comparingLong(Location::seenAt).reversed()).toList();
	}

	/** 去掉一条记录（按 samePlace 比较）。有变化时立刻保存。 */
	public synchronized void remove(UUID noteId, Location location) throws IOException {
		List<Entry> updated = new ArrayList<>(entries);
		if (updated.removeIf(entry -> entry.noteId().equals(noteId) && entry.location().samePlace(location))) {
			save(updated);
			entries = updated;
		}
	}

	private static void validate(Location location) {
		Objects.requireNonNull(location);
		String dimension = Objects.requireNonNull(location.dimension());
		if (dimension.isEmpty() || dimension.indexOf('\t') >= 0 || dimension.indexOf('\n') >= 0
				|| dimension.indexOf('\r') >= 0 || location.slot() < -1 || location.slot() > 5) {
			throw new IllegalArgumentException("Invalid location");
		}
	}

	private void save(List<Entry> updated) throws IOException {
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
		try {
			try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				for (Entry entry : updated) {
					Location location = entry.location();
					writer.write(entry.noteId() + "\t" + location.dimension() + "\t" + location.x() + "\t" + location.y()
							+ "\t" + location.z() + "\t" + location.slot() + "\t" + location.seenAt() + "\n");
				}
			}
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
