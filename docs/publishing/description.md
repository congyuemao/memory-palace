**Build a memory palace in Minecraft: keep Markdown and LaTeX notes on the bookshelves in your world.**

"The third bookshelf on the second floor of the library" can be the physical address of a note. The world stores only a pointer, and each note remains an ordinary `.md` file next to your save.

- If a creeper destroys the bookshelf, your note is safe.
- The vault is an ordinary folder. Open it in Obsidian or Typora, and your changes appear in the game right away.
- If you uninstall the mod, each note book remains a normal written book that shows the file name of its note.

## Features

**Read and write**
- Note books use the vanilla written book, and they fit in chiseled bookshelves and lecterns.
- Two-page reader with adjustable page and font sizes.
- Markdown support: headings, lists, task lists, quotes, code blocks, tables, links, and local images.
- LaTeX support for inline and display formulas.
- Obsidian-style links, including `[[note]]`, `[[folder/note|text]]`, and `[[note#heading]]`, with link completion and backlinks.
- In-game editor with live preview, undo and redo, autosave, and conflict warnings.

**Find your notes**
- Press `K` to capture an idea to your inbox.
- Press `J` to search titles, folders, and note text. A light beam and direction arrow lead you to the bookshelf.
- Aim at a bookshelf slot to see which note is there.

**Keep your notes safe**
- Encrypted notes use Argon2id and AES-256-GCM, and decryption happens only on the client. Passwords are not stored.
- Folder passwords, read-only note passwords, recovery keys, and FIDO2 security keys (Windows only).
- Version history for in-game saves, with viewing and restore.
- Incremental backups outside the world folder each time you enter a world, with restore from the game or the offline tool.
- Offline tool for decrypting notes and restoring backups without starting the game, available from [GitHub Releases](https://github.com/congyuemao/memory-palace/releases).

**Multiplayer (off by default)**
- Per-note read and edit permissions on LAN worlds and servers.
- Personal folders, inboxes, and keyrings for each player.
- Permission nodes for LuckPerms and similar mods, plus per-player limits.
- Server administrators can read unencrypted notes on disk. Only encryption protects a note from an administrator.

## Requirements

- Minecraft Java Edition 26.3
- Fabric Loader 0.20.1 or later
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu)

The mod is required on the client. To use it on a server, install it on the server as well, and use the same version on the server and clients.

## Getting started

1. In a world, run `/palace new math/eigenvalues`. This creates a note, gives you its note book, and opens the editor.
2. Write some Markdown, and press `Ctrl+S` to save.
3. Put the book in a chiseled bookshelf, and right-click that slot with an empty hand to read it.

For all commands and details, see the [full documentation](https://github.com/congyuemao/memory-palace#readme).

## Languages

English and Simplified Chinese, plus machine-generated Traditional Chinese, Japanese, Korean, Russian, Spanish, French, German, and Brazilian Portuguese. Corrections are welcome.

## Credits and license

Developed with the assistance of Claude Code and OpenAI Codex. Inspired by [ins@aamanda.li](https://www.instagram.com/aamanda.li)'s [meme reel](https://www.instagram.com/reel/Dd4gs6UTB0w/). Licensed under [MIT](https://github.com/congyuemao/memory-palace/blob/main/LICENSE). Bundled libraries are listed in the [third-party notices](https://github.com/congyuemao/memory-palace/blob/main/THIRD_PARTY_NOTICES.md).

---

# 简体中文

**在 Minecraft 中建造记忆宫殿：将 Markdown 和 LaTeX 笔记存放在世界中的书架上。**

“图书馆二楼第三个书架”可以成为一篇笔记的物理地址。世界中只保存指针，笔记本身始终是存档旁的普通 `.md` 文件。

- 书架被苦力怕炸毁后，笔记不会丢失。
- 笔记库是一个普通文件夹，可以使用 Obsidian 或 Typora 打开和编辑，修改会立即显示在游戏中。
- 卸载模组后，笔记书仍是普通成书，书页上会显示对应笔记的文件名。

## 主要功能

**阅读与书写**
- 笔记书基于原版成书，可放入雕纹书架和讲台。
- 提供左右两页的阅读界面，可分别调整书页大小和字号。
- 支持标题、列表、任务清单、引用、代码块、表格、链接和本地图片等 Markdown 语法。
- 支持行内和行间 LaTeX 公式。
- 支持 `[[笔记]]`、`[[文件夹/笔记|文字]]` 和 `[[笔记#标题]]` 等 Obsidian 风格的链接，并提供链接补全和反向链接。
- 提供带实时预览的游戏内编辑器，支持撤销、重做、自动保存和冲突提示。

**查找笔记**
- 按 `K` 可将想法快速记录到收件箱。
- 按 `J` 可搜索标题、文件夹和正文，并通过光柱和方向箭头前往对应书架。
- 将准星对准书架格子，即可查看其中的笔记。

**保护笔记**
- 加密笔记使用 Argon2id 和 AES-256-GCM，解密仅在客户端进行，且不保存密码。
- 支持文件夹密码、单篇只读密码、恢复密钥和 FIDO2 实体钥匙（仅限 Windows）。
- 游戏内保存时会保留历史版本，可以查看和还原。
- 每次进入世界时，都会在存档之外创建增量备份，并可通过游戏或离线工具恢复。
- 离线工具无需启动游戏即可解密笔记和恢复备份，可从 [GitHub Releases](https://github.com/congyuemao/memory-palace/releases) 下载。

**多人游戏（默认关闭）**
- 在局域网世界和服务器上，可按笔记设置阅读和编辑权限。
- 每位玩家拥有独立的个人区、收件箱和钥匙圈。
- 支持 LuckPerms 等模组使用的权限节点，并可限制每位玩家的用量。
- 服务器管理员可以在硬盘上读取未加密的笔记。只有加密才能防止管理员读取笔记。

## 运行要求

- Minecraft Java 版 26.3
- Fabric Loader 0.20.1 或更高版本
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25
- 可选：[Mod Menu](https://modrinth.com/mod/modmenu)

客户端必须安装本模组。如需在服务器上使用，服务器也须安装，且服务端和客户端的版本必须相同。

完整的命令和说明，请参阅[中文文档](https://github.com/congyuemao/memory-palace/blob/main/README.zh-CN.md)。
