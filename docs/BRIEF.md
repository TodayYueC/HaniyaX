# HaniyaX — original product brief (from the user, verbatim)

请基于 Termux 开发 **HaniyaX**，一款面向开发者、免 Root、可独立安装并与原版 Termux 共存的 Android Linux 工作台。保留 Termux 的终端兼容性和软件生态，深度重做手机上的界面与操作体验。HaniyaX 本身就是图形操作入口，产品范围不包含 Linux 图形桌面。用户手动启动工作区后，终端、Agent、项目文件和后台服务共用同一套 Linux 环境。

**1. 全局设计沿用 Haniya Relief UI。** 开发前先阅读仓库中的 `SKILL.md`、`references/` 和 `compose/`，以现有设计规范、组件和示例作为视觉依据。首页、终端、Agent 管理、DSH 内嵌页和第三方首页模块都跟随同一套主题。尤其要把终端正文纳入主题，背景、光标、选区、快捷键栏和 ANSI 基础色都应协调，同时保证命令输出、报错和代码差异清晰可读。重点打磨竖屏、中文输入和软键盘弹出后的操作空间。设计仓库：TodayYueC/haniya-relief-ui（本仓库 docs/design/ 与 relief/ 模块）

**2. 首页围绕"继续工作"组织。** 默认提供终端、Agent 管理、DeepSeek Harness 三个入口，再提供少量真正有用的模块：最近项目、进行中的任务、后台服务与日志。点项目可以在对应目录开终端或启动 Agent；点正在运行的任务可以回到原会话；点服务可以看状态、地址、日志和启停操作。用户可以隐藏、排序、固定模块，也可以把常用项目或任务设为打开 App 后的默认入口。

**3. Codex、OpenCode 和 Claude Code 采用按需安装。** Agent 管理页默认展示这三项，APK 只内置适配与管理能力。未安装时在对应条目显示「安装」，已安装时显示版本和「启动」，发现兼容新版时额外显示「更新」。识别工作区中用户自行安装的版本，展示安装进度和有用的错误信息。点击启动后，直接在选定项目目录打开该 Agent 的真实交互式终端；已有会话时可选择继续或新开。更新由用户决定，保留配置、登录状态和历史，避免打断正在执行的任务。

**4. 把终端和多任务体验做完整。** 普通 Shell 和三个终端 Agent 都支持多标签，可以在同一项目并行运行任务，也可以在不同项目间切换。完整保留各工具实际支持的登录、斜杠命令、流式输出、工具调用、审批、Diff、取消和历史恢复。手机端重点做好中文输入法、长文本粘贴预览、选取复制、回滚搜索、可定制扩展键和全屏 TUI。明确区分「离开页面」「关闭标签」和「结束任务」：切换标签、返回首页或锁屏时，进程尽可能继续运行；回来时连接原会话。若 Android 已终止进程，显示真实状态，并按工具能力提供恢复或重新启动。

**5. 提供统一的模型与服务商管理。** 可以直接基于 CC Switch 的开源实现改造，复用成熟的配置读写和服务商切换逻辑，在 HaniyaX 内提供移动端界面。用户可以按 Agent 管理服务商、模型、API 地址和认证方式，看到当前实际生效的配置。尊重各工具的差异，区分官方账号登录与自定义 API 配置；说明变更对当前会话还是新会话生效，并允许恢复上一份可用配置。参考项目：https://github.com/farion1231/cc-switch

**6. 为 DeepSeek Harness 单独做一个内嵌工作页。** DSH 同样由用户点击安装和更新。进入该页时，后台自动启动或连接已有 DSH 服务，直接在 HaniyaX 内呈现适合手机操作的界面。优先将这套移动前端做成可独立维护的 DSH 插件，复用 DSH 的会话、工具、审批和工作区能力，按 Haniya Relief UI 重新组织导航、对话输入、执行详情和文件变更，让用户在竖屏上也能完整处理一次任务。无需跳转浏览器，也无需手动输入 `dsh web`。分别管理 DSH 本体与前端插件的版本，更新前检查兼容性。参考项目：https://github.com/deepseek-ai/deepseek-harness

**7. 让用户能用一句话开发自己的首页模块。** 在 Linux 工作区提供开放、带版本的插件接口，随环境附上本地文档、示例和开发命令。任何具备文件读写和命令执行能力的 Agent 都可以用它查询已有模块、创建、预览、安装、修改和移除插件。比如用户在 Codex 里说"首页加一张卡，显示我的开发服务器是否在线，点一下看最近 20 行日志"，完成后回到首页就能使用；第二天换 Claude Code，也能继续修改同一张卡。插件默认继承宿主主题，支持导出分享，项目路径和凭据留在各用户自己的环境里。仅展示信息的模块和能够执行命令的模块，应让用户清楚区分。

请自主选择技术实现，优先复用成熟的上游能力，把主要精力放在手机端交互、真实进程管理和插件体验上。交付可安装、可运行的 APK，并用一条完整流程验收：**从首页安装一个 Agent，在项目中启动它，切到 DSH 处理另一项工作，再回原标签完成审批，最后让 Agent 创建一张重开 App 后依然可用的首页卡片。**

## Decisions confirmed with the user (planning phase)
- Linux environment: single proot + Debian arm64 (glibc) workspace; official Linux builds of every tool. No Termux `pkg`; software via Debian `apt`.
- Reuse only Termux `terminal-emulator` + `terminal-view` (Apache-2.0). Session service, extra keys, app shell are rewritten (no GPL code).
- Acceptance on a real arm64 phone.
