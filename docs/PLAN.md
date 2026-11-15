# HaniyaX 执行计划

## Context

HaniyaX 是一款免 Root、与原版 Termux 共存的 Android 开发者工作台：首页、终端、Agent 管理（Codex / OpenCode / Claude Code）、DeepSeek Harness 内嵌页、统一服务商管理、可由 Agent 一句话开发的首页卡片。项目目录目前为空（未 `git init`）。

调研后与你确认的三个前提，决定了整体架构：

| 决定 | 结论 | 原因 |
|---|---|---|
| Linux 环境 | **proot + Debian arm64 单一环境** | 改包名后 Termux 官方源不可用；Claude Code、OpenCode、DSH 均无 Android 原生构建（DSH 的文件锁在 `android` 平台直接抛错）。glibc 环境里四个工具都能用官方 Linux 版 |
| Termux 复用范围 | **仅 `terminal-emulator` + `terminal-view`（Apache-2.0）** | 会话服务、扩展键、App 壳自己重写，许可证由你自定 |
| 验收设备 | **arm64 真机为主** | 需要你在手机上允许 USB 调试（当前 `365fbd2f` 为未授权） |

与原始设想的偏差（已获确认）：没有 Termux 的 `pkg`，软件用 Debian `apt`；proot 下 git、npm 等密集读写会比原生慢。

## 架构

```
HaniyaX/
├─ app/                    Kotlin + Compose 主应用（applicationId dev.haniya.x，minSdk 26，targetSdk 28，arm64-v8a）
├─ relief/                 设计系统模块：haniya-relief-ui 的 compose/ 原样拷入，包名改 dev.haniya.relief
├─ terminal-emulator/      Termux 上游拷贝（Apache-2.0，记录上游 commit 于 UPSTREAM.md）
├─ terminal-view/          同上
├─ native/proot/           用 NDK 28 静态构建 proot + loader → jniLibs/libproot.so、libproot-loader.so
├─ workspace/              随 APK 进入工作区的内容
│   ├─ rootfs/             构建脚本，产出 debian-arm64 rootfs 压缩包（APK asset）
│   ├─ hx/                 `hx` 命令行（bash + curl，调用 App 的本机控制接口）
│   ├─ adapters/           codex / opencode / claude / dsh 四个适配器（声明 + 脚本）
│   └─ sdk/                插件接口文档、JSON Schema、示例卡片
├─ dsh-mobile/             DSH 移动前端插件（独立 npm 包，可单独发版）
└─ tools/                  构建、验收脚本
```

- **targetSdk 28** 沿用 Termux 的做法，保证可执行工作区内的二进制；因此走侧载分发，不上 Google Play。
- **进程模型**：单进程。前台服务 `WorkspaceService` 持有全部 pty 会话与后台服务进程，Activity 只是视图。所有进程经 `libproot.so` 进入同一 rootfs，`$HOME` 相同。
- **不引入 tmux**：它会接管回滚和选区，破坏「回滚搜索、选取复制」；而 App 进程被系统杀掉时它同样活不下来。被杀后的恢复靠各 Agent 自己的续接命令。
- **App ↔ 工作区控制接口**：App 在 `127.0.0.1` 随机端口开一个带令牌的 HTTP 接口（令牌文件权限 0600），`hx` 通过它查询和操作；App 同时监听 `~/.haniyax/` 目录变化。

工作区内的约定目录：`~/.haniyax/{plugins,services,projects.json,backups}`，SDK 与适配器在 `/opt/haniyax/`。

## 各项需求的设计

### 1. 主题（Haniya Relief UI）
- 全应用包在 `ReliefTheme` 内，只用 `glass()` / `insetSurface()` / `gradientSurface()` 及现成组件（`GlassCard`、`ListRow`、`HChip`、`SegmentedControl`、`GlassSheet`、`GlassBottomNav` 等）；图标只用 `ReliefIcons` 手绘集，缺的按 `references/icons.md` 补路径。
- 默认跟随系统：浅色 → 铂雾，深色 → 曜石；五套调色板可选。依赖 Compose BOM 2026.09+。
- **终端配色**（规范未覆盖，由我推导并做成 `TerminalPalette.from(ReliefColors)`）：
  - 背景 = `bg`，前景 = `ink`，光标 = `mid`，选区 = `soft`；快捷键栏为凸起薄层，按下内凹。
  - ANSI 16 色按每套调色板单独调校：色相取自调色板气质，普通色对背景对比度 ≥ 4.5:1，红/绿（报错、diff）保证可区分；红色取 `danger` 色相。规范的「不用警示红」约束的是界面，不约束终端内容。
  - 响应 OSC 10/11 查询并设置 `COLORFGBG`，让 Agent 的 TUI 能识别浅/深底色。
- DSH 内嵌页和首页卡片通过同一份令牌取色：WebView 注入 CSS 变量（`materials.md` 已给出 CSS 配方），卡片由宿主原生渲染。
- 以 `references/checklist.md` 对真机截图逐项检查（浅色、深色各一轮）。

### 2. 首页「继续工作」
- 三个固定入口：终端、Agent 管理、DeepSeek Harness。
- 内置模块：最近项目、进行中的任务、后台服务与日志。它们与第三方卡片走同一个卡片宿主。
- 项目 → 底部面板：在该目录开终端 / 选 Agent 启动。任务 → 回到原标签。服务 → 状态、地址、日志尾部、启停。
- 长按进入编辑：隐藏、排序、固定；任一项目或任务可设为「打开 App 的默认入口」。布局存 DataStore。
- 服务登记在 `~/.haniyax/services/<name>.json`（命令、目录、端口、日志路径），由 `WorkspaceService` 托管，输出落盘到日志文件。

### 3. Agent 按需安装
- 每个适配器声明：检测命令、版本命令、最新版来源、已验证版本范围、安装/更新脚本、启动与续接命令、配置路径。

| Agent | 安装方式（在 Debian 内） | 续接 |
|---|---|---|
| Claude Code | 官方原生安装脚本 | `claude --continue` / `--resume` |
| Codex | 官方发布的 linux-arm64 musl 二进制 | `codex resume --last` |
| OpenCode | 官方安装脚本（v2） | `opencode --continue`（待验证） |

- 条目状态：未安装 →「安装」；已安装 → 版本 +「启动」；有已验证范围内的新版 → 追加「更新」；超出范围的新版标「未验证」。检测走登录 Shell 的 `PATH`，因此用户自行安装的版本也能识别。
- 安装在一个可见的任务会话里执行：界面显示步骤清单与实时日志，失败时给出最后若干行输出和可操作的原因（无网络、空间不足、架构不符）。
- 启动：选项目目录 → 直接打开真实交互式终端；该项目有历史会话时给「继续 / 新开」。
- 更新由用户点击触发；有运行中的该 Agent 会话时先提示。只动程序文件，不碰 `~/.claude`、`~/.codex`、`~/.config/opencode` 等配置与历史。

### 4. 终端与多任务
- `TerminalView` 以 `AndroidView` 嵌入 Compose；会话模型 `Task`（类型、项目、命令、pid、状态、Agent 会话标识）与 `Tab`（视图）分离。
- 三种操作明确区分：
  - **离开页面**（返回、回首页、锁屏）：进程继续，任务留在「进行中」。
  - **关闭标签**：只收起视图，任务继续运行，可从任务列表回来。
  - **结束任务**：二次确认后 SIGHUP → SIGTERM → SIGKILL。
- App 进程被系统终止后：任务记录保留并标记「已被系统结束」，按适配器能力提供「恢复会话」或「重新启动」；不伪装成仍在运行。
- 前台服务 + 可选唤醒锁；首次使用时给出 Android 12+「子进程限制」的关闭指引（该限制会无提示杀掉后台子进程）。
- 手机端输入：
  - 中文输入：除直接输入外，提供「撰写栏」模式（键盘上方的内凹输入框，写完再发送），规避 IME 组字与 TUI 的冲突。
  - 长文本粘贴：超过阈值先弹预览面板，确认后以 bracketed paste 发送。
  - 选取复制、回滚全文搜索（基于终端缓冲区）、可定制扩展键（自写 Compose 实现，配置存 JSON）、全屏 TUI 模式。
  - 软键盘弹出时：标签栏收成一行，终端按可见区域重算行列。

### 5. 模型与服务商管理
- **引擎首选 `SaladDay/cc-switch-cli`**（MIT，CC Switch 的命令行移植，提供静态 linux-arm64 包）：固定版本装入工作区，HaniyaX 的界面调用它完成服务商增删与切换。
- **备选**：若它缺少可供程序调用的输出，则把 CC Switch 的「只改关键字段」逻辑移植到 Kotlin（来源：`src-tauri/src/live/floor.rs`、`live/project/*.rs`、`live/patch/*`、`live/engine.rs`），覆盖 Claude / Codex / OpenCode / DSH。预设列表取自上游 `src/config/*ProviderPresets.ts`（保留 MIT 署名）。
- 界面按 Agent 分页：服务商、模型、API 地址、认证方式。
  - 「官方账号登录」与「自定义 API」分为两类卡片；官方类不写密钥字段，登录在终端里走各工具自己的流程。
  - 「当前生效」直接读各工具的实际配置文件，并标出会覆盖它的环境变量。
  - 每次切换前快照相关文件到 `~/.haniyax/backups/<agent>/<时间>/`，提供「恢复上一份可用配置」。
  - 切换后说明生效范围；有运行中会话时列出它们并提示需重开才生效。

### 6. DeepSeek Harness 内嵌页
- DSH 本体：`npm i -g @deepseek-ai/dsh`（需 Node ≥ 24.9，安装 DSH 时一并装入官方 Node）。
- 进入页面：App 把 `dsh web --no-open --port <端口>` 作为托管服务启动，或复用仍存活的实例；解析启动输出中的带令牌地址，在内嵌 WebView 打开。
- 移动前端 `dsh-mobile/`：独立 DSH 插件（`package.json` 的 `dsh` 字段 + `engines.dsh` 兼容范围），注册独立路由前缀提供移动端单页应用，不抢占内置前端的兜底路由。复用 DSH 的客户端连接包访问会话、工具、审批、工作区接口。
- 竖屏信息架构：会话列表 → 对话（底部输入）→ 执行详情与审批（底部面板）→ 文件变更（逐文件 diff）。
- 版本分别管理：DSH 本体与插件各显示版本；更新 DSH 前用插件的 `engines.dsh` 校验目标版本，不兼容则阻止并说明。
- **退路**：DSH 处于开发者预览，接口可能变动。若客户端包无法在插件内复用，第一版先加载内置前端并注入 Relief 样式，移动前端作为后续迭代，届时会明确告知。

### 7. 首页插件接口
- 插件 = `~/.haniyax/plugins/<id>/plugin.json`，声明式、带 `apiVersion: 1`，由宿主用 Relief 组件原生渲染，因此自动继承主题：
  - **数据源**：`file`、`http`（仅本机）、`port`、`process`、`command`（带刷新间隔）。
  - **展示块**：状态点、文本、键值、日志尾部、进度、迷你折线。
  - **动作**：打开终端并执行命令、运行命令并在面板显示输出、打开项目、启动 Agent、打开本机地址。
- **两类卡片的区分**：只用 `file/http/port/process` 的是「仅展示」；含 `command` 数据源或命令动作的是「可执行命令」，卡片带标识，首次启用须在 App 内逐条确认命令原文，命令变更后重新确认。确认只能在界面上完成，Agent 无法替用户批准。
- `hx` 命令：`hx plugin list | new | validate | preview | install | remove | export | import`，`hx service …`，`hx docs`。`preview` 返回卡片在当前数据下的文本渲染，Agent 不看屏幕也能自检。
- 文档、Schema、示例随环境放在 `/opt/haniyax/sdk/`；安装 Agent 时写入一条指向它的简短说明（Claude 技能、Codex/OpenCode 的 AGENTS 说明），可在设置中关闭。
- 导出为 `.hxcard`：清单声明 `params`（项目路径、端口等），其取值存在本机的 `local.json`，不进导出包；凭据只按环境变量名引用。导入时提示填写参数。

## 实施顺序

**M0 可行性验证（先做，决定后续细节）**
1. 骨架 App 在真机上用内置 proot 启动 Debian，跑通 `bash`、`apt`、网络解析。
2. 在该环境中逐个安装并启动 Claude Code、Codex、OpenCode、DSH，记录各自的障碍。
3. `TerminalView` 嵌入 Compose，并确认 Relief 的阴影在目标设备上渲染正常。
4. 确认 `cc-switch-cli` 是否可供程序调用；确认 DSH 插件可注册路由并复用客户端包。

任何一项不成立都会回到你这里，带着具体现象和替代方案。

**M1** 工程骨架、`relief` 模块、工作区创建（解压 rootfs，带进度）、`WorkspaceService`、终端与标签、终端配色。
**M2** Agent 适配器、安装/更新/启动/续接、任务模型与三种关闭语义。
**M3** 首页、项目、任务、服务与日志、默认入口。
**M4** 服务商管理。
**M5** DSH 托管与内嵌页、`dsh-mobile` 插件。
**M6** 插件接口、`hx`、SDK 文档与示例、导出导入。
**M7** 输入体验打磨、截图走查、真机验收、签名 APK。

每个里程碑结束提交一次（先 `git init`）。

## 需要你参与的地方
- 在手机上允许 USB 调试。
- 验收时登录各 Agent 或提供 API Key（Claude、Codex/OpenAI、DeepSeek）；我不会代填凭据。
- 构建机需要补装的工具（Rust 仅在走 Kotlin 移植以外的路线时需要；rootfs 构建可能需要 podman/qemu）会在用到时先说明。

## 已知风险
- **沙箱**：Codex 的 Landlock 与 DSH 的 bwrap 在 proot 内很可能不可用，需要降低沙箱级别才能运行。这会以明确的开关呈现给你，不会悄悄关闭。
- **proot 性能**：大仓库的 git 与 npm 安装偏慢。
- **系统杀后台**：Android 12+ 子进程限制与厂商省电策略只能引导用户关闭，无法从 App 内绕过。
- **上游变动**：DSH 为预览版；OpenCode v2 的命令与包名需以实际安装为准。
- **APK 体积**：内置 rootfs 后预计 60–100 MB。

## 验证
- 单元测试：配置补丁与快照恢复、插件清单校验与「仅展示 / 可执行」分类、适配器版本比较、终端配色对比度。
- 每个里程碑在真机上用 `adb` 安装并截图，按 `references/checklist.md` 检查浅色与深色。
- **最终验收（真机，一条流程）**：
  1. 全新安装 APK，创建工作区。
  2. 首页 → Agent 管理 → 安装一个 Agent，看到进度与版本。
  3. 在某项目目录启动它，发起一个会触发审批的任务。
  4. 返回首页进入 DSH，页面自动连上服务，完成另一项任务。
  5. 从「进行中的任务」回到原标签，会话仍在，完成审批。
  6. 让 Agent 用 `hx` 创建一张「开发服务器是否在线，点击看最近 20 行日志」的卡片，回首页确认命令后可用。
  7. 强制停止并重开 App，卡片仍在且可用；原任务显示真实状态并可恢复。
  8. 换另一个 Agent 修改同一张卡片，改动生效。
