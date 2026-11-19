# HaniyaX

面向开发者的 Android Linux 工作台：免 Root、与原版 Termux 共存（包名 `dev.haniya.x`），终端、Claude Code / Codex / OpenCode、DeepSeek Harness 与后台服务共用同一套 Linux 环境。界面采用 Haniya Relief UI。

原始需求见 `docs/BRIEF.md`，执行计划见 `docs/PLAN.md`。

## 构建

```bash
./tools/fetch-assets.sh                 # proot（Termux 软件源，RUNPATH 已改为 $ORIGIN）+ Debian trixie arm64 rootfs
ARCH=x86_64 ./tools/fetch-assets.sh     # 可选：调试版用的 x86_64 运行时（模拟器）
./gradlew :app:assembleRelease          # dist 用的正式版（arm64）
./gradlew :app:testDebugUnitTest        # 单元测试
```

需要 JDK 17、Android SDK（compileSdk 37.2、NDK 28.2）、podman（导出 rootfs）。
正式版签名读取 `keystore/haniyax-release.jks` 与 `keystore/keystore.properties`（均不进 git）；缺失时使用调试签名。

## 结构

| 位置 | 内容 |
|---|---|
| `app/` | 主应用（Kotlin + Compose）。`runtime/` proot 工作区、任务与前台服务；`terminal/` 终端；`home/` 首页、项目；`agents/` 智能体；`providers/` 服务商（移植 CC Switch 的关键字段逻辑）；`dsh/` DeepSeek Harness 内嵌页；`plugins/` 首页卡片；`control/` 供 `hx` 使用的本机接口；`files/` 文件管理与系统文件提供者 |
| `app/src/main/assets/sdk/` | 安装到工作区 `/opt/haniyax` 的 `hx` 命令、文档、卡片模板、智能体与 DSH 安装脚本 |
| `relief/` | Haniya Relief UI 设计系统（Compose） |
| `terminal-emulator/`、`terminal-view/` | Termux 终端模块（Apache-2.0），本地修改见 `UPSTREAM.md` |

## 工作区

- proot + Debian trixie（glibc），以 root 运行，`HOME=/root`；项目默认在 `~/projects`。
- targetSdk 28，以便执行应用目录中的程序；因此通过 APK 侧载分发。
- 进程由前台服务持有：离开页面、锁屏后继续运行；Android 回收应用后，任务如实显示「已被系统结束」，可恢复或重新启动。
- 工作区以「HaniyaX」出现在系统文件选择器中；也可授权访问手机存储，把手机里的文件夹作为项目。

## 已知限制

- **沙箱**：proot 中没有 bubblewrap / Landlock，Codex 与 DeepSeek Harness 的系统沙箱不可用；启动面板与 DSH 菜单中有明确的完全访问选项与说明。
- **DSH 前端**：使用 DSH 自带的网页界面，由 HaniyaX 注入 Relief 配色（映射 DSH 的设计令牌）。按 Relief 重新组织导航的独立 DSH 插件尚未实现。
- **后台存活**：Android 12+ 的子进程限制与厂商省电策略可能结束后台进程，设置页提供关闭指引，应用内无法绕过。
- **性能**：proot 下 git、npm 等大量文件操作比原生慢；手机存储（/sdcard）不支持符号链接与可执行权限。
