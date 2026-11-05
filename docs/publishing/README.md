# 发布资料

本文件夹保存向模组平台发布时使用的资料。

| 文件 | 用途 |
|---|---|
| `description.md` | CurseForge 和 Modrinth 的项目介绍（英文在前，中文在后） |
| `release-notes.md` | 当前版本的更新说明，`publishMods` 会读取此文件 |
| `mcmod.md` | MC百科的条目资料 |

## 项目信息

| 字段 | 内容 |
|---|---|
| 名称 | Memory Palace |
| 标识（slug） | `memory-palace`；如已被占用，可使用 `memory-palace-notes` |
| 英文简介 | Keep Markdown and LaTeX notes on bookshelves in your world, and turn it into a memory palace. |
| 中文简介 | 将 Markdown 和 LaTeX 笔记存放在世界中的书架上，打造属于自己的记忆宫殿。 |
| 许可证 | MIT |
| 图标 | `art/icon/icon-512.png` |
| 加载器 | Fabric |
| 游戏版本 | 26.3 |
| 必需依赖 | Fabric API |
| 可选依赖 | Mod Menu |
| 运行环境 | 客户端必需；服务端可选（如需在服务器上使用，须同时安装） |
| 建议分类 | CurseForge：Utility & QoL；Modrinth：Utility（请以平台当前选项为准） |
| 源代码 | https://github.com/congyuemao/memory-palace |
| 问题反馈 | https://github.com/congyuemao/memory-palace/issues |

## AI 声明

- **Modrinth**：必须启用“Contains AI-generated content”声明。内容主要或完全由 AI 产出的项目不能公开发布。发布前，建议先向 Modrinth 确认本项目是否符合规则，并询问由 AI 编写的脚本绘制的图标是否可用。
- **CurseForge**：现行审核政策只要求为可能造成误导的 AI 展示图添加声明。提交前，请再次核对政策。
- **图库图片**：只使用游戏内的真实截图，不使用 AI 生成或修改的图片。

## 需要手动准备的截图

建议使用英文界面和示例笔记，不要包含个人内容。

1. 摆满笔记书的书架，准星对准一格并显示书名
2. 显示 Markdown 和 LaTeX 公式的阅读界面
3. 左侧编辑、右侧预览的编辑器
4. 显示光柱和方向箭头的“找笔记”功能
5. 在 Obsidian 中打开的同一个笔记库
6. 输入密码的加密笔记界面

## 发布步骤

1. 在游戏中完成回归测试。
2. 拍摄截图。
3. 在 CurseForge 和 Modrinth 上创建项目，填写上表，粘贴 `description.md`，然后上传图标和截图。
4. 将项目 ID 填入 `gradle.properties` 的 `curseforge_project_id` 和 `modrinth_project_id`。不发布的平台请留空。
5. 设置环境变量 `CURSEFORGE_TOKEN` 和 `MODRINTH_TOKEN`。请勿将令牌写入仓库。
6. 运行 `./gradlew publishMods`。如果缺少已配置平台的令牌，此命令只会试运行，不会上传。
7. 在 GitHub Releases 上发布相同版本，并附上模组和离线工具的 JAR。
8. 在 MC百科提交条目（见 `mcmod.md`）。
