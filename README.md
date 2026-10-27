# Memory Palace · 记忆宫殿

一个 Minecraft Java 版（Fabric）模组：把 Markdown / LaTeX 笔记放进世界里的书架上，搭建属于自己的记忆宫殿。

笔记以普通 `.md` 文件保存在存档旁边，世界里的"笔记书"只记录一个 ID——书架被苦力怕炸掉，笔记也不会丢失；在 Obsidian 等外部编辑器中修改，游戏里即时刷新。

## 功能

- **笔记书与书架**：笔记书即原版成书，可放入原版雕纹书架（每架 6 本）或讲台。右键阅读，潜行 + 右键取出。卸载模组后，书仍是普通成书，书页上写有对应的笔记文件名。
- **原版书本风格阅读界面**：左右两页摊开的原版书，书页大小与字体大小可分别调整（书页变大时字号不变、每页行数增多）。
- **Markdown 与 LaTeX**：标题、列表、任务清单、引用、代码块、表格、链接；行内公式 `$...$` 与行间公式 `$$...$$` 渲染为图片；支持本地图片（`![说明](图.png)` 与 Obsidian 的 `![[图.png]]`）。
- **游戏内编辑器**：左页编辑、右页实时预览；`Ctrl+S` 保存；外部修改会提示冲突。
- **加密笔记（密码模式）**：Argon2id + AES-256-GCM；密码不保存；解密仅在客户端进行；可选恢复密钥；闲置自动上锁。
- **快速收集**：按 `K` 记下一个想法，自动写入当天的 `收件箱/yyyy-MM-dd.md`（写下第一条时才创建文件）。
- **找笔记与导航**：按 `J` 搜索标题、文件夹与正文，选中后以光柱与方向箭头（含上下高度差）引导至书架。
- **准星显示书名**：对准书架上的某一格时显示书名。
- **自动备份**：每次进入世界，将笔记库备份至存档之外（`.minecraft/memory-palace-backups/`）。

## 安装

需要：

- Minecraft Java 版 **26.3**
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 或更高
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25（官方启动器自带）
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu)（在模组列表中打开设置）

从 [Releases](../../releases) 下载 `memorypalace-x.y.z.jar`，与 Fabric API 一同放入 `.minecraft/mods/` 文件夹。

## 使用

| 操作 | 说明 |
|---|---|
| `/palace give <笔记或文件夹>` | 领取笔记书（文件夹会领取其中每一篇） |
| `/palace new <路径>` | 新建笔记并在游戏内编辑 |
| `/palace newsecret <路径>` | 新建加密笔记 |
| `/palace encrypt` / `decrypt <笔记>` | 加密 / 解除加密（解除时会打开笔记，点书下方的「解密」，再输入密码并确认） |
| `/palace lock` | 立即上锁加密笔记 |
| `/palace where <笔记>` | 查看笔记所在的书架与讲台 |
| `/palace list` / `rescan` | 列出笔记 / 重新扫描笔记库 |
| `/palace visibility [everyone\|private]` | 查看 / 修改笔记可见性（管理员） |
| `M` / `K` / `J` | 打开笔记窗口 / 快速收集 / 找笔记 |

按键均可在"选项 → 控制 → 按键绑定 → 记忆宫殿"中修改；进入世界时会提示与其他按键的冲突。

笔记库位于 `saves/<世界>/memory-palace/`，可直接用 Obsidian 打开。

**多人游戏（局域网、服务器）**：整个世界共用一个笔记库，权限按篇计算。

- **谁能读**：默认"所有人可读"——不加锁的笔记，世界里的人都能看；带锁（加密）的笔记只有作者能打开。管理员可用 `/palace visibility private` 改成"默认私有"。
- **谁能改**：只有作者。以前的笔记没有作者，归管理员（房主，或 2 级以上权限的 OP）。
- 单篇笔记可在开头的 front matter 里写 `palace-owner`（作者）、`palace-read`、`palace-edit`（`everyone` 或逗号分隔的玩家 UUID）来指定。
- **收件箱永远私有**，目前只有房主能用；加密、领书、新建笔记也暂时只限房主或管理员。
- 拿到别人的笔记书不等于能看：服务端每次都会检查权限。不公开的笔记，书名只显示"【私密笔记】#7f3a"这类占位文字；更新前领的旧书，要在被使用、放上书架或讲台被右键时才会改成新书名。
- 图片跟着所在文件夹走：图片所在的文件夹及其上层文件夹里只要有你读不了的笔记，你就看不到这张图。**私密笔记请放在子文件夹里**，否则根目录有一篇私密笔记，别人就看不到任何图片。
- 在外部（Obsidian 等）改了 `palace-read` / `palace-edit`，约 2 秒内生效；新放进笔记库的文件需要管理员 `/palace rescan`。
- **没加密的笔记，服务器管理员可以直接在硬盘上看到**（局域网里就是房主）。只有加密能防住管理员。

快速收集按玩家所在地的本地日期归档；每天第一条及时区变化时，会标注时区（如 `(UTC+8)`）。自动备份以 UTC 时间命名，不受时区与夏令时影响。

## 关于加密

- 密码不会以任何形式保存；遗忘密码时只能使用恢复密钥重置。未设置恢复密钥时，遗忘密码将导致加密笔记永久无法打开。
- 恢复密钥等同于第二把钥匙，请妥善保管。
- 文件名、加密笔记中引用的图片不加密。
- 修改密码不会使旧备份失效：旧备份配合旧密码仍可解开当时的笔记。

## 从源码构建

```bash
./gradlew build
```

产物位于 `build/libs/`。开发时用 `./gradlew runClient` 启动测试客户端。需要 JDK 25。

## 第三方库

模组 jar 内打包了以下库：

- [commonmark-java](https://github.com/commonmark/commonmark-java)（BSD-2-Clause）
- [JLaTeXMath](https://github.com/opencollab/jlatexmath)（GPL-2.0 with Classpath Exception）
- [Password4j](https://github.com/Password4j/password4j)（Apache-2.0）

## 许可

[MIT](LICENSE)

本项目在 [Claude Code](https://claude.com/claude-code) 与 OpenAI Codex 的协助下开发。
