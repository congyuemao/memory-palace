package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 记录"每篇笔记在世界里被看到过的位置"。一篇笔记可以有多个位置（书可以复制、挂在好几处）。
 * 只在玩家从书架/讲台打开笔记时自动记录；用的时候由调用方检查位置是否还有效，无效就 remove。
 * 存在 &lt;笔记库&gt;/.palace/locations.tsv（隐藏文件夹，不会被当成笔记扫描）。
 * 线程安全：公开方法可以从多个线程调用。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class NoteLocations {
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
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * 记录"在 location 看到了笔记 noteId"。同一个地方已有记录就更新时间，不重复添加。
	 * 同一个地方（同维度同坐标同格子）只能属于一篇笔记：如果之前记录的是别的笔记，把那条去掉。
	 * 立刻保存到文件（原子写：临时文件 + move）。
	 */
	public void record(UUID noteId, Location location) throws IOException {
		throw new UnsupportedOperationException("TODO");
	}

	/** 这篇笔记的所有位置，最近看到的排在前面。没有记录时为空列表。 */
	public List<Location> get(UUID noteId) {
		throw new UnsupportedOperationException("TODO");
	}

	/** 去掉一条记录（按 samePlace 比较）。有变化时立刻保存。 */
	public void remove(UUID noteId, Location location) throws IOException {
		throw new UnsupportedOperationException("TODO");
	}
}
