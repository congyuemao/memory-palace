package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

/**
 * 把笔记库打包成 zip 备份到存档外面，防止删世界连笔记一起删掉。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class VaultBackup {
	private VaultBackup() {}

	/**
	 * 备份 vaultRoot 整个文件夹到 backupDir/&lt;时间&gt;.zip。要求：
	 * - 文件名格式 "yyyy-MM-dd_HH-mm-ss.zip"，时间取自 clock（用 clock 的时区）；同一秒内已存在同名文件时加 "_2"、"_3"…
	 * - zip 里的路径相对 vaultRoot，用 "/" 分隔，保留子文件夹；空文件夹不用保留
	 * - 包括隐藏文件夹（.palace/ 里的位置记录也要备份），但跳过名字以 ".palace-" 开头、".tmp" 结尾的临时文件
	 * - 原子写入：先写 backupDir 里的临时文件，写完再 move 成正式文件名；中途失败不能留下正式文件名的半成品，也不留临时文件
	 * - backupDir 不存在时自动创建
	 * - 如果笔记库里所有文件的最后修改时间都不晚于最新一个已有备份的修改时间（即"自上次备份以来没改过"），
	 *   则不备份，返回 empty
	 * - 笔记库为空（没有任何文件）时不备份，返回 empty
	 * - 备份完成后清理：backupDir 里符合上面文件名格式的 .zip 只保留最新的 keep 个（按文件名排序，旧的删掉）；
	 *   不符合格式的文件一律不动
	 * - 单个文件读取失败：跳过它继续（不要让一个被占用的文件毁掉整个备份）
	 * @return 新建的备份文件；没有备份时为 empty
	 */
	public static Optional<Path> backup(Path vaultRoot, Path backupDir, int keep, Clock clock) throws IOException {
		throw new UnsupportedOperationException("TODO");
	}
}
