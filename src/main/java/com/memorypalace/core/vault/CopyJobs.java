package com.memorypalace.core.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 抄进被锁文件夹的进行中记录（见 FolderCopy），由服务端自己记，存在 .palace/copy-jobs.tsv（服务器重启也接得上）。
 * 抄本物品上只存记录的随机 ID：物品数据可以伪造，不能拿来当凭证——伪造的、别人的 ID 都查不到（find 要核对玩家），
 * 查不到就只能另建一篇新的目标，绝不会去写一篇已有的笔记。
 * 每人最多留 MAX_PER_PLAYER 条（旧的丢掉），超过 MAX_AGE 的丢掉。线程安全。
 * 注意：本包（core/）不允许 import 任何 net.minecraft / net.fabricmc 的类。
 */
public final class CopyJobs {
	static final int MAX_PER_PLAYER = 20;
	static final long MAX_AGE_MILLIS = 30L * 24 * 3600 * 1000;

	/**
	 * 一条记录：谁（player）把哪篇（source）抄进哪篇占位（target），占位正文的指纹，目标文件夹当时的锁 ID，建立时间。
	 */
	public record Job(UUID id, UUID player, UUID source, UUID target, String placeholderHash, String lockId, long createdAt) {}

	private final Path file;
	private final LongSupplier clock;
	private List<Job> jobs;

	public CopyJobs(Path file) {
		this(file, System::currentTimeMillis);
	}

	/** 测试用：自己给时钟（毫秒）。 */
	public CopyJobs(Path file, LongSupplier clock) {
		this.file = file;
		this.clock = clock;
	}

	/** 新建一条记录，返回它（ID 随机）。 */
	public synchronized Job start(UUID player, UUID source, UUID target, String placeholderHash, String lockId) throws IOException {
		List<Job> all = new ArrayList<>(load());
		Job job = new Job(UUID.randomUUID(), player, source, target, placeholderHash, lockId, clock.getAsLong());
		all.add(job);
		// 每人只留最新的 MAX_PER_PLAYER 条
		List<Job> mine = all.stream().filter(j -> j.player().equals(player)).sorted(Comparator.comparingLong(Job::createdAt)).toList();
		for (int i = 0; i < mine.size() - MAX_PER_PLAYER; i++) {
			all.remove(mine.get(i));
		}
		save(all);
		return job;
	}

	/** 这个人的这条记录；不存在、不是这个人的、过期了 → empty。 */
	public synchronized Optional<Job> find(UUID id, UUID player) throws IOException {
		return load().stream().filter(j -> j.id().equals(id) && j.player().equals(player)).findFirst();
	}

	/** 这个人正抄进 target 的记录。 */
	public synchronized Optional<Job> byTarget(UUID target, UUID player) throws IOException {
		return load().stream().filter(j -> j.target().equals(target) && j.player().equals(player)).findFirst();
	}

	/** 抄好了（或者作废了）：删掉这条。 */
	public synchronized void finish(UUID id) throws IOException {
		List<Job> all = new ArrayList<>(load());
		if (all.removeIf(j -> j.id().equals(id))) {
			save(all);
		}
	}

	private List<Job> load() throws IOException {
		if (jobs == null) {
			List<Job> read = new ArrayList<>();
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					parse(line).ifPresent(read::add);
				}
			} catch (NoSuchFileException e) {
				// 还没有记录
			}
			jobs = read;
		}
		long now = clock.getAsLong();
		jobs = jobs.stream().filter(j -> now - j.createdAt() < MAX_AGE_MILLIS).toList();
		return jobs;
	}

	private static Optional<Job> parse(String line) {
		String[] f = line.split("\t", -1);
		if (f.length != 7 || !f[4].matches("[0-9a-f]{64}") || !f[5].matches("[A-Za-z0-9+/=,_-]{1,128}")) {
			return Optional.empty();
		}
		try {
			return Optional.of(new Job(UUID.fromString(f[0]), UUID.fromString(f[1]), UUID.fromString(f[2]), UUID.fromString(f[3]),
					f[4], f[5], Long.parseLong(f[6])));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private void save(List<Job> all) throws IOException {
		StringBuilder out = new StringBuilder();
		for (Job j : all) {
			out.append(j.id()).append('\t').append(j.player()).append('\t').append(j.source()).append('\t').append(j.target())
					.append('\t').append(j.placeholderHash()).append('\t').append(j.lockId()).append('\t').append(j.createdAt()).append('\n');
		}
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".palace-", ".tmp");
		try {
			Files.writeString(temporary, out, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
		jobs = List.copyOf(all);
	}
}
