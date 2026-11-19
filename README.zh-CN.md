# HaniyaX

[English](README.md) | **简体中文**

面向开发者的 Android Linux 工作台：免 Root，与原版 Termux 共存（包名 `dev.haniya.x`）。终端、编程智能体（Claude Code、Codex、Antigravity、OpenCode）、DeepSeek Harness 与后台服务共用同一套 Debian 环境。界面采用 Haniya Relief UI 设计系统。

界面语言默认跟随系统（中文系统显示中文，其他语言显示英文），可在「设置 → 语言」中更改。

## 功能

- **继续工作**：底部导航在首页、项目、智能体、服务、设置之间切换，右侧圆键打开终端。首页只放进行中的任务、最近项目和首页卡片；任一项目或任务都可以设为打开 App 时的默认入口。
- **终端**：多标签、支持中文输入法、撰写栏、长文本粘贴预览、选取复制、回滚搜索、可自定义扩展键、全屏。「离开页面」「关闭标签」「结束任务」三者分开；被系统结束的任务如实显示，可恢复会话。
- **按需安装智能体**：在任意项目中安装、更新、启动、续接 Claude Code、Codex、Antigravity、OpenCode。是否更新由你决定，设置、登录与历史都会保留。
- **服务商管理**：基于 CC Switch 的关键字段模型，按智能体管理 API 地址、密钥与模型；切换前自动快照，可一键恢复。
- **DeepSeek Harness**：按需安装，内嵌在应用中，并套用 Relief 配色。
- **首页卡片**：智能体可以用 `hx` 命令开发首页卡片。卡片是声明式 JSON，由应用原生渲染；会运行命令的卡片必须先在应用内确认。
- **文件**：应用内文件管理；在系统文件选择器中以「HaniyaX」出现；也可授权访问手机存储，把手机里的文件夹作为项目。

## 安装

从 [Releases](https://github.com/TodayYueC/HaniyaX/releases) 下载 APK（arm64）。HaniyaX 的 targetSdk 为 28，以便执行应用目录中的程序，因此通过侧载分发，不上架 Google Play。

首次启动时会解压内置的 Debian 系统（完成后约 250 MB），并从 Debian 软件源安装基础软件。

## 构建

```bash
./tools/fetch-assets.sh                 # proot（Termux 软件源，RUNPATH 已改为 $ORIGIN）+ Debian trixie arm64 rootfs
ARCH=x86_64 ./tools/fetch-assets.sh     # 可选：调试版用的 x86_64 运行时（模拟器）
./gradlew :app:assembleRelease          # 正式版（arm64）
./gradlew :app:testDebugUnitTest        # 单元测试
```

需要 JDK 17、Android SDK（compileSdk 37.2、NDK 28.2）、podman（导出 rootfs）。
正式版签名读取 `keystore/haniyax-release.jks` 与 `keystore/keystore.properties`（均不进 git）；缺失时使用调试签名。

## 结构

| 位置 | 内容 |
|---|---|
| `app/` | 主应用（Kotlin + Compose）。`runtime/` proot 工作区、任务与前台服务；`terminal/` 终端；`home/` 首页、项目；`agents/` 智能体；`providers/` 服务商（移植 CC Switch 的关键字段逻辑）；`dsh/` DeepSeek Harness 内嵌页；`plugins/` 首页卡片；`control/` 供 `hx` 使用的本机接口；`files/` 文件管理与系统文件提供者 |
| `app/src/main/assets/sdk/` | 安装到工作区 `/opt/haniyax` 的 `hx` 命令、文档、卡片模板、智能体与 DSH 安装脚本 |
| `app/src/main/assets/i18n/` | 界面翻译：`source.txt`（中文原文）与逐行对应的 `en.txt` |
| `relief/` | Haniya Relief UI 设计系统（Compose）与运行时翻译层 |
| `terminal-emulator/`、`terminal-view/` | Termux 终端模块（Apache-2.0），本地修改见 `UPSTREAM.md` |

### 补充或修正翻译

界面文案在源码中以中文书写，运行时翻译。`en.txt` 的每一行对应 `source.txt` 的同一行，需保留 `{0}`、`{1}` 等占位符。`I18nCatalogTest` 会检查行数对齐与占位符。

## 工作区

- proot + Debian trixie（glibc），以 root 运行，`HOME=/root`；项目默认在 `~/projects`。
- 进程由前台服务持有：离开页面、锁屏后继续运行；Android 回收应用后，任务显示「已被系统结束」，可恢复或重新启动。

## 已知限制

- **沙箱**：proot 中没有 bubblewrap / Landlock，Codex 与 DeepSeek Harness 的系统沙箱不可用；启动面板与 DSH 菜单中有明确的完全访问选项与说明。
- **Antigravity 登录**：通过浏览器登录 Google 账号，或使用 Gemini API Key（在 `~/.gemini/antigravity-cli/settings.json` 中设置 `modelProvider: "gemini"`，并设置 `GEMINI_API_KEY`）。
- **DSH 前端**：使用 DSH 自带的网页界面并注入 Relief 配色；独立的移动前端尚未实现。
- **后台存活**：Android 12+ 的子进程限制与厂商省电策略可能结束后台进程，设置页提供关闭指引，应用内无法绕过。
- **性能**：proot 下 git、npm 等大量文件操作比原生慢；手机存储（`/sdcard`）不支持符号链接与可执行权限。

## 许可证

[Apache License 2.0](LICENSE)。第三方组件及其许可证见 [NOTICE](NOTICE) 与 [UPSTREAM.md](UPSTREAM.md)。
