<p align="center">
  <img src="art/icon/icon-512.png" alt="Memory Palace 图标" width="160">
</p>

<h1 align="center">Memory Palace · 记忆宫殿</h1>

<p align="center">
  把 Markdown / LaTeX 笔记放进 Minecraft 世界里的书架上，搭一座属于自己的记忆宫殿。<br>
  Minecraft Java 版 26.3 · Fabric · MIT
</p>

<p align="center">
  <a href="../../releases/latest">下载最新版</a>
</p>

---

"图书馆二楼第三个书架"可以成为一篇笔记的物理地址。世界里只放**指针**，笔记本体永远是存档旁边的普通 `.md` 文件：

- 书架被苦力怕炸掉，笔记不会丢；卸载模组，书还是普通的成书，书页上写着对应的文件名。
- 笔记库就是一个普通文件夹，可以直接用 Obsidian、Typora 打开编辑，游戏里即时刷新。
- 备份笔记 = 备份一个文件夹。

Inspired by [ins@aamanda.li](https://www.instagram.com/aamanda.li)'s [meme reel](https://www.instagram.com/reel/Dd4gs6UTB0w/).

## 功能

**阅读与书写**
- **笔记书**：就是原版成书，放进原版雕纹书架（每架 6 本）或讲台。右键阅读，潜行 + 右键取出。
- **原版书本风格的阅读界面**：左右两页摊开，书页大小和字号可分别调整。
- **Markdown**：标题、列表、任务清单、引用、代码块、表格、链接、本地图片（`![说明](图.png)` 与 Obsidian 的 `![[图.png]]`）。
- **LaTeX**：行内公式 `$...$`，行间公式 `$$...$$`。
- **游戏内编辑器**：左页编辑、右页实时预览，`Ctrl+S` 保存，`Ctrl+Z` 撤销，`Ctrl+Y`（或 `Ctrl+Shift+Z`）重做；文件在外部被改过时会提示冲突。

**记忆宫殿**
- **快速收集**：按 `K` 记下一个想法，写进当天的 `收件箱/yyyy-MM-dd.md`，之后再整理归位。
- **找笔记**：按 `J` 搜索标题、文件夹和正文，选中后用光柱和方向箭头（含上下高度差）带你走到那个书架。
- **准星显示书名**：对准书架上的某一格，就能看到那本书是哪篇笔记。
- **`/palace where`**：查一篇笔记放在哪些书架和讲台上，点坐标会把传送命令填进聊天框。

**安全**
- **加密笔记**：Argon2id + AES-256-GCM，密码不保存，解密只在客户端进行；可选恢复密钥，闲置自动上锁。
- **自动备份**：每次进入世界，把笔记库备份到存档之外，删掉世界也不会连笔记一起丢。
- **多人游戏**：局域网、服务器上按每篇笔记控制谁能读、谁能改，可以分享给指定玩家（默认关闭，见下文）。

## 安装

需要：

- Minecraft Java 版 **26.3**
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 或更高
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25（官方启动器自带）
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu)，在模组列表里打开设置

从 [Releases](../../releases) 下载 `memorypalace-x.y.z.jar`，和 Fabric API 一起放进 `.minecraft/mods/`。

## 快速上手

1. 进入世界，输入 `/palace new 数学/特征值`：新建一篇笔记，背包里多一本笔记书，并直接打开编辑器。
2. 写几行 Markdown，`Ctrl+S` 保存。
3. 把书插进雕纹书架，空手右键那一格就能阅读。
4. 想在电脑上写：打开 `saves/<世界>/memory-palace/`，用 Obsidian 当作 vault 打开即可。外部改完，游戏里立刻刷新。
5. 已有的 `.md` 文件直接放进这个文件夹，输入 `/palace rescan`，再用 `/palace give <路径>` 领书。

## 按键与命令

| 按键 | 作用 |
|---|---|
| `M` | 打开笔记窗口 |
| `K` | 快速收集 |
| `J` | 找笔记 |

按键可在"选项 → 控制 → 按键绑定 → 记忆宫殿"中修改；进入世界时会提示与其他按键的冲突。

| 命令 | 说明 |
|---|---|
| `/palace give <笔记或文件夹>` | 领取笔记书；文件夹会领出里面每一篇（单次最多 36 本） |
| `/palace new <路径>` | 新建笔记并在游戏内编辑 |
| `/palace list [文件夹]` | 列出笔记 |
| `/palace where <笔记>` | 查看笔记所在的书架与讲台 |
| `/palace share <玩家\|everyone> read\|edit <笔记>` | 把自己的笔记分享给别人（只读 / 可编辑） |
| `/palace unshare <玩家\|everyone> <笔记>` | 取消分享 |
| `/palace newsecret <路径>` | 新建加密笔记 |
| `/palace encrypt <笔记>` | 加密一篇笔记 |
| `/palace decrypt <笔记>` | 解除加密：会打开这篇笔记，点书下方的「解密」，再输入密码确认 |
| `/palace lock` | 立即给加密笔记上锁 |
| `/palace rescan` | 重新扫描笔记库（管理员） |
| `/palace visibility [everyone\|private]` | 查看 / 修改本世界的笔记可见性（管理员） |
| `/palace reload` | 重新读取服务端配置文件（管理员） |

命令里的笔记路径可以省略 `.md`，可以含空格和中文；领书、查位置、分享等命令按 `Tab` 可补全笔记路径。

## 笔记库

```
saves/<世界>/memory-palace/        ← 笔记库，可直接用 Obsidian 打开
├─ 数学/特征值.md
├─ 收件箱/2026-10-27.md            ← 快速收集
├─ players/<玩家UUID>/…           ← 多人游戏时玩家各自的个人区（含自己的收件箱）
└─ .palace/                        ← 模组自己的数据（位置记录、钥匙圈、世界设置）
```

每篇笔记开头有一段 front matter，模组靠里面的 `palace-id` 认出它，所以在外部**改名、移动文件夹都不会断链**：

```markdown
---
palace-id: 7f3a2c1e-…
palace-owner: <作者 UUID>          # 以下三项只在多人游戏时用到，都可以省略
palace-read: everyone             # 谁能读：everyone 或逗号分隔的玩家 UUID
palace-edit: <UUID>, <UUID>       # 作者之外谁能改
---
# 特征值
$$A\mathbf{v} = \lambda \mathbf{v}$$
```

## 多人游戏（局域网、服务器）

**默认关闭。** 关闭时只有本机房主能用笔记功能，其他玩家什么都看不到；单人游戏不受影响。专用服务器上没有房主，关闭时谁都不能用。

### 开启

编辑 `config/memorypalace-server.toml`（第一次启动世界或服务器时自动生成，带注释），把 `multiplayer` 改成 `true`，然后由管理员执行 `/palace reload`（专用服务器上要重启）。配置文件写坏、读取失败时，一律按多人关闭处理。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `multiplayer` | `false` | 多人总开关 |
| `default_visibility` | `"private"` | 没写 `palace-read` 的笔记谁能读：`"private"`（作者和被分享的人）或 `"everyone"`（所有人）。世界里用 `/palace visibility` 设置过的，以世界设置为准 |
| `max_notes_per_player` | `200` | 每个玩家最多建多少篇笔记，`0` = 不限 |
| `max_note_chars` | `200000` | 普通玩家保存的一篇笔记最多多少字，`0` = 不限 |

管理员不受篇数和字数限制。

### 权限节点

用 [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) 判断（已打包，无需另装）。装了 LuckPerms 等权限模组时，服主可以按玩家组分配；没装时按下表的默认值。

| 节点 | 默认 | 作用 |
|---|---|---|
| `memorypalace.admin` | 本机房主、2 级及以上 OP | 领任意笔记的书、在任意位置新建、重扫、改可见性、重读配置 |
| `memorypalace.create` | 所有人 | 在自己的个人区新建笔记 |
| `memorypalace.give` | 所有人 | 领自己读得了的笔记的书 |
| `memorypalace.share` | 所有人 | 分享、取消分享自己的笔记 |

### 规则

- **新建**：普通玩家的笔记建在自己的个人区 `players/<UUID>/` 下，作者是自己；命令里输入的路径先在个人区里找。本机房主照旧建在输入的位置。
- **读和改**：作者能读能改；`palace-edit` 里的人能改也能读；`palace-read` 里的人能读。没有作者的笔记（以前的笔记、房主建的笔记、收件箱）归本机房主，专用服务器上归管理员。
- **分享**：只有作者能分享。加密笔记和收件箱不能分享。`/palace unshare everyone <笔记>` 把公开笔记改回私有；没有"黑名单"，笔记还对所有人公开时，单独去掉某个人，他照样能读。
- **书只是门牌，不是钥匙**：拿到别人的笔记书什么也打不开，服务端每次都会检查权限。开放局域网或在服务器上时，不公开的笔记书名只显示"【私密笔记】#7f3a"这类占位文字。
- **图片**：图片必须和引用它的笔记在同一区域（同一个人的个人区，或都不在个人区里）；图片所在文件夹及上层文件夹里有你读不了的笔记，你就看不到这张图。私密笔记请放在子文件夹里。
- **同时编辑**：不支持多人实时同步编辑。两人同时修改同一篇，后保存的人会收到冲突提示。
- **收件箱**：每人一个。房主的在 `收件箱/`，其他玩家的在自己的个人区 `players/<UUID>/收件箱/`，只有本人能看。目前收件箱是明文保存的，服务器管理员在硬盘上看得到。
- **加密**：每人一个钥匙圈、一个保险库密码（房主的在 `.palace/keyring`，其他玩家的在 `.palace/keyrings/<UUID>`），只能加密自己是作者的笔记。加密之后这篇只有作者能打开，原来分享给别人的权限也随之失效。
- 在外部改了 `palace-read` / `palace-edit`，约 2 秒内生效；新放进笔记库的文件需要管理员 `/palace rescan`。

### 注意

- **没加密的笔记，服务器管理员可以直接在硬盘上看到**（局域网里就是房主）。只有加密能防住管理员。
- 书名是写在书上的。**纯单人游戏时领的书仍是真实标题**，开放局域网前请把放着私密笔记的书收好。

## 加密

- 一个保险库密码管你所有的加密笔记（多人游戏时每人各有一个）。密码不以任何形式保存；忘记密码只能用恢复密钥重设。没设恢复密钥时，忘记密码 = 加密笔记永久打不开。
- 恢复密钥等于第二把钥匙，请妥善保管。
- 文件名、加密笔记引用的图片不加密。加密笔记的书名只显示"【加密笔记】#7f3a"。
- 改密码不会让旧备份失效：旧备份配合旧密码仍能解开当时的笔记。

## 备份

- 每次进入世界，把笔记库打包到存档之外的 `.minecraft/memory-palace-backups/<世界>/`，保留最近 10 份；内容没变就不重复备份。
- 备份以 UTC 时间命名，不受时区和夏令时影响。
- 快速收集按玩家所在地的本地日期归档；每天第一条和时区变化时，会标注时区（如 `(UTC+8)`）。

## 从源码构建

需要 JDK 25。

```bash
./gradlew build        # 产物在 build/libs/
./gradlew runClient    # 启动开发用客户端
```

## 第三方库

模组 jar 内打包了以下库：

- [commonmark-java](https://github.com/commonmark/commonmark-java)（BSD-2-Clause）
- [JLaTeXMath](https://github.com/opencollab/jlatexmath)（GPL-2.0 with Classpath Exception）
- [Password4j](https://github.com/Password4j/password4j)（Apache-2.0）
- [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api)（MIT）
- [NightConfig](https://github.com/TheElectronWill/night-config)（LGPL-3.0）

## 许可

[MIT](LICENSE)

本项目在 [Claude Code](https://claude.com/claude-code) 与 OpenAI Codex 的协助下开发。
