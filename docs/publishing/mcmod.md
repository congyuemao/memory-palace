# MC百科条目资料

提交前，请查阅 MC百科站内的当前编辑规范。

| 字段 | 内容 |
|---|---|
| 中文名称 | 记忆宫殿 |
| 英文名称 | Memory Palace |
| 模组 ID | `memorypalace` |
| 类型 | 实用 |
| 运行环境 | 客户端必需，服务端可选 |
| 前置模组 | Fabric API |
| 支持版本 | Fabric 26.3 |
| 开源协议 | MIT |
| 作者 | congyuemao |
| 相关链接 | https://github.com/congyuemao/memory-palace |

## 一句话简介

将 Markdown 和 LaTeX 笔记存放在世界中的书架上，打造属于自己的记忆宫殿。

## 模组介绍

记忆宫殿是一款笔记模组。玩家可以将笔记书放入雕纹书架或讲台，把世界中的位置变成笔记的“物理地址”。世界中只保存指针，笔记本身始终是存档旁的普通 `.md` 文件，可以使用 Obsidian 或 Typora 直接打开和编辑。

主要功能：

- 阅读和编辑 Markdown 与 LaTeX 笔记，支持实时预览、撤销和自动保存
- 使用 Obsidian 风格的双向链接
- 通过搜索、光柱和方向箭头前往笔记所在的书架
- 使用 Argon2id 和 AES-256-GCM 加密笔记，并支持文件夹密码、阅读密码和恢复密钥
- 保留历史版本，并在存档之外自动创建增量备份
- 在局域网世界和服务器上按笔记设置权限（默认关闭）

完整说明请参阅[中文文档](https://github.com/congyuemao/memory-palace/blob/main/README.zh-CN.md)。
