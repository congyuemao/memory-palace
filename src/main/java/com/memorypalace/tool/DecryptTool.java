package com.memorypalace.tool;

import com.memorypalace.core.vault.VaultBackup;
import com.memorypalace.core.vault.VaultDecryptor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 笔记小工具（不开游戏）：
 * - 解密：用密码或恢复密钥把加密笔记解成明文另存一份（原文件不动）
 * - 从备份恢复：把 memory-palace-backups 里某一次备份还原成一个完整的笔记库文件夹（见 VaultBackup）
 * 双击 jar（或 java -jar memory-palace-decrypt.jar）打开窗口；
 * 命令行：java -jar memory-palace-decrypt.jar &lt;笔记文件夹&gt; [输出文件夹]，密码在终端里输入（不回显）；
 * java -jar memory-palace-decrypt.jar --restore &lt;备份文件夹&gt; [备份版本]：从备份恢复（默认最新一次）。
 * 和模组本身无关，不 import 任何 net.minecraft 的类。
 */
public final class DecryptTool {
	private static final String PROGRESS = "正在解密，请稍候。打开每个密钥约需 1 至 2 秒。";
	private static final String WARNING = "注意：明文文件可被任何人读取。查看完毕后，请及时删除。";
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter IN_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

	private DecryptTool() {}

	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			System.exit(runCli(args));
		}
		SwingUtilities.invokeLater(DecryptTool::showWindow);
	}

	private static int runCli(String[] args) throws IOException {
		if (args[0].equals("--restore") && (args.length == 2 || args.length == 3)) {
			Path backupDir = Path.of(args[1]);
			List<VaultBackup.Snapshot> snapshots = VaultBackup.snapshots(backupDir);
			if (snapshots.isEmpty()) {
				System.err.println("所选文件夹中没有备份。请选择 memory-palace-backups 下对应世界的文件夹。");
				return 2;
			}
			VaultBackup.Snapshot snapshot = args.length == 3
					? snapshots.stream().filter(x -> x.id().equals(args[2])).findFirst().orElse(null) : snapshots.getLast();
			if (snapshot == null) {
				System.err.println("找不到指定的备份版本：" + args[2]);
				return 2;
			}
			System.out.println(restore(backupDir, snapshot));
			return 0;
		}
		if (args.length > 2 || args[0].startsWith("-")) {
			System.out.println("用法：java -jar memory-palace-decrypt.jar <笔记文件夹> [输出文件夹]");
			System.out.println("      java -jar memory-palace-decrypt.jar --restore <备份文件夹> [备份版本]");
			System.out.println("笔记文件夹应为 memory-palace 文件夹，或其所在的世界存档文件夹；");
			System.out.println("备份文件夹为 memory-palace-backups 下对应世界的文件夹，备份版本默认为最新一次。");
			return args[0].equals("-h") || args[0].equals("--help") ? 0 : 2;
		}
		Console console = System.console();
		if (console == null) {
			System.err.println("未检测到终端，无法安全地输入密码。请双击 JAR 文件以使用图形界面。");
			return 2;
		}
		Path vault = vaultOf(Path.of(args[0]));
		Path output = args.length > 1 ? Path.of(args[1]) : defaultOutput(vault);
		char[] secret = console.readPassword("请输入密码或恢复密钥：");
		if (secret == null || secret.length == 0) {
			return 2;
		}
		try {
			System.out.println(PROGRESS);
			System.out.println(report(VaultDecryptor.decrypt(vault, output, secret)));
			return 0;
		} finally {
			Arrays.fill(secret, '\0');
		}
	}

	private static void showWindow() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			// 用默认外观
		}
		JFrame frame = new JFrame("记忆宫殿笔记工具");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		JTextField folder = new JTextField(36);
		JButton choose = new JButton("浏览...");
		JComboBox<SnapshotItem> versions = new JComboBox<>();
		JButton restore = new JButton("从备份恢复");
		JPasswordField password = new JPasswordField(36);
		JButton run = new JButton("解密");
		JTextArea log = new JTextArea(12, 50);
		log.setEditable(false);
		log.setLineWrap(true);
		log.setText("""
				解密：请选择笔记文件夹（memory-palace 文件夹，或其所在的世界存档文件夹），输入在游戏中设置的密码或恢复密钥，然后单击“解密”。解密后的笔记将以明文形式保存到同级目录下的“（解密版）”文件夹中，原始文件不会被修改。

				从备份恢复：请选择 memory-palace-backups 下对应世界的文件夹，选择备份版本，然后单击“从备份恢复”。恢复的笔记库将保存到同级目录下的新文件夹中，其中的加密笔记仍保持加密，可以接着解密。

				""" + WARNING);
		Runnable refreshVersions = () -> {
			versions.removeAllItems();
			List<VaultBackup.Snapshot> snapshots = List.of();
			if (!folder.getText().isBlank()) {
				try {
					snapshots = VaultBackup.snapshots(Path.of(folder.getText().strip()));
				} catch (IOException | RuntimeException e) {
					snapshots = List.of();
				}
			}
			for (int i = snapshots.size() - 1; i >= 0; i--) {
				versions.addItem(new SnapshotItem(snapshots.get(i), i == snapshots.size() - 1));
			}
			versions.setEnabled(!snapshots.isEmpty());
			restore.setEnabled(!snapshots.isEmpty());
		};
		refreshVersions.run();
		folder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				refreshVersions.run();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				refreshVersions.run();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				refreshVersions.run();
			}
		});
		restore.addActionListener(e -> {
			if (!(versions.getSelectedItem() instanceof SnapshotItem item)) {
				return;
			}
			Path backupDir = Path.of(folder.getText().strip());
			restore.setEnabled(false);
			log.setText("正在从备份恢复，请稍候。");
			new SwingWorker<Path, Void>() {
				private String text;

				@Override
				protected Path doInBackground() throws Exception {
					Path target = restoreTarget(backupDir, item.snapshot());
					text = report(target, VaultBackup.restore(backupDir, item.snapshot().id(), target));
					return target;
				}

				@Override
				protected void done() {
					try {
						Path target = get();
						log.setText(text);
						folder.setText(target.toString()); // 接着就能解密恢复出来的笔记库
					} catch (Exception ex) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						log.setText("恢复失败：" + cause.getMessage());
						restore.setEnabled(true);
					}
				}
			}.execute();
		});

		choose.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser(folder.getText().isBlank() ? null : folder.getText());
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
				folder.setText(chooser.getSelectedFile().getPath());
			}
		});
		run.addActionListener(e -> {
			if (folder.getText().isBlank() || password.getPassword().length == 0) {
				log.setText("请先选择笔记文件夹，并输入密码或恢复密钥。");
				return;
			}
			Path vault = vaultOf(Path.of(folder.getText().strip()));
			if (!Files.isDirectory(vault)) {
				log.setText("找不到指定的文件夹：" + vault);
				return;
			}
			char[] secret = password.getPassword();
			run.setEnabled(false);
			log.setText(PROGRESS);
			new SwingWorker<String, Void>() {
				@Override
				protected String doInBackground() throws Exception {
					try {
						return report(VaultDecryptor.decrypt(vault, defaultOutput(vault), secret));
					} finally {
						Arrays.fill(secret, '\0');
					}
				}

				@Override
				protected void done() {
					run.setEnabled(true);
					try {
						log.setText(get());
						password.setText("");
					} catch (Exception ex) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						log.setText("解密失败：" + cause.getMessage());
					}
				}
			}.execute();
		});

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		c.gridx = 0;
		c.gridy = 0;
		form.add(new JLabel("笔记文件夹："), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(folder, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(choose, c);
		c.gridx = 0;
		c.gridy = 1;
		form.add(new JLabel("备份版本："), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(versions, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(restore, c);
		c.gridx = 0;
		c.gridy = 2;
		form.add(new JLabel("密码或恢复密钥："), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(password, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		form.add(run, c);

		JScrollPane scroll = new JScrollPane(log);
		scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 12, 12, 12), scroll.getBorder()));
		frame.getRootPane().setDefaultButton(run);
		frame.add(form, BorderLayout.NORTH);
		frame.add(scroll, BorderLayout.CENTER);
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	/** 选的是世界存档文件夹（里面有 memory-palace）就用里面那个。 */
	static Path vaultOf(Path chosen) {
		Path inner = chosen.resolve("memory-palace");
		return !Files.isDirectory(chosen.resolve(".palace")) && Files.isDirectory(inner) ? inner : chosen;
	}

	/** 同级目录下的"…（解密版）"文件夹；已经有了就加编号，不覆盖。 */
	static Path defaultOutput(Path vault) {
		Path abs = vault.toAbsolutePath().normalize();
		Path parent = abs.getParent() != null ? abs.getParent() : abs;
		String base = abs.getFileName() != null ? abs.getFileName().toString() : "笔记";
		Path out = parent.resolve(base + "（解密版）");
		for (int i = 2; Files.exists(out); i++) {
			out = parent.resolve(base + "（解密版 " + i + "）");
		}
		return out;
	}

	/** 下拉框里的一次备份：显示本地时间，最新的一次标出来。 */
	private record SnapshotItem(VaultBackup.Snapshot snapshot, boolean newest) {
		@Override
		public String toString() {
			return SHOWN.format(snapshot.time().atZone(ZoneId.systemDefault())) + (newest ? "（最新）" : "");
		}
	}

	/** 恢复到备份文件夹旁边："世界名（备份 2026-10-30 14-05-12）"；已经有了就加编号，不覆盖。 */
	private static Path restoreTarget(Path backupDir, VaultBackup.Snapshot snapshot) {
		Path abs = backupDir.toAbsolutePath().normalize();
		Path parent = abs.getParent() != null ? abs.getParent() : abs;
		String base = (abs.getFileName() != null ? abs.getFileName().toString() : "笔记") + "（备份 "
				+ IN_NAME.format(snapshot.time().atZone(ZoneId.systemDefault()));
		Path out = parent.resolve(base + "）");
		for (int i = 2; Files.exists(out); i++) {
			out = parent.resolve(base + " " + i + "）");
		}
		return out;
	}

	private static String restore(Path backupDir, VaultBackup.Snapshot snapshot) throws IOException {
		Path target = restoreTarget(backupDir, snapshot);
		return report(target, VaultBackup.restore(backupDir, snapshot.id(), target));
	}

	private static String report(Path target, VaultBackup.Restored r) {
		StringBuilder s = new StringBuilder("已从备份恢复 ").append(r.files()).append(" 个文件。\n");
		s.append("保存位置：").append(target).append('\n');
		if (!r.broken().isEmpty()) {
			s.append("\n以下文件的备份内容缺失或已损坏，未能恢复：\n");
			r.broken().forEach(f -> s.append("  ").append(f).append('\n'));
		}
		s.append("\n如需解密其中的加密笔记，请输入密码或恢复密钥，然后单击“解密”。");
		return s.toString();
	}

	private static String report(VaultDecryptor.Result r) {
		StringBuilder s = new StringBuilder();
		if (r.keys() == 0) {
			s.append("提供的密码或恢复密钥无法打开任何密钥。请确认输入是否正确，并确认所选文件夹为完整的 memory-palace 文件夹（密钥文件位于其 .palace 子文件夹中）。\n\n");
		}
		s.append("已解密 ").append(r.decrypted()).append(" 篇笔记，已复制 ").append(r.plain()).append(" 篇未加密的笔记。\n");
		s.append("保存位置：").append(r.output()).append('\n');
		if (!r.failed().isEmpty()) {
			s.append("\n以下笔记无法解密（可能属于其他玩家，或使用了其他密码，例如文件夹密码）：\n");
			r.failed().forEach(f -> s.append("  ").append(f).append('\n'));
		}
		s.append("\n").append(WARNING);
		return s.toString();
	}
}
