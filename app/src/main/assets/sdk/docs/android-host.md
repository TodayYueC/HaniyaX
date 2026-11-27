# Android 宿主访问 / Android host access

HaniyaX 的工作区是 Debian PRoot，工作区里的 `id -u` 通常显示模拟的 UID 0。
`haniyax host` 通过 HaniyaX 的 Android 服务发起宿主请求。“普通访问”使用 HaniyaX 的 Android 应用 UID，权限受 Android 和 SELinux 约束。“Root 访问”需要用户主动选择，并由设备的 `su` 管理器授予真正的 Android UID 0。

在 **设置 → Android 宿主访问** 开启。每次 HaniyaX 进程重新启动，访问模式回到禁用，授权策略保留。安装新 APK 后，SDK 会刷新到新版。用 `haniyax host status` 或 `haniyax host info` 查询实时模式、策略、Android 应用 UID、Root 检测状态和活动会话数。

```sh
haniyax host status
haniyax host shell "getprop"
haniyax host shell "pm list packages"
haniyax host root "id"
haniyax host root "settings list system"
haniyax host shell
haniyax host root
haniyax host --root push ./test.apk /data/local/tmp/test.apk
haniyax host pull /sdcard/test.txt ./test.txt
haniyax host install ./test.apk
haniyax host apps
haniyax host --root logcat
haniyax host --root settings get system screen_brightness
haniyax host --help
```

- `shell` 始终使用 Android 应用 UID。支持的结构化命令可以通过 `--root` 显式选择 UID 0。`install` 通过 Root 下的 Android 包管理器安装单个 APK。
- 不带脚本的 `shell` 和 `root` 创建 Android PTY，输出合并。带脚本的调用支持分离的 stdout、stderr、stdin 和宿主退出码。使用 Ctrl-C 中断管道调用，交互模式中用 Ctrl-C 中断前台命令，用 `exit` 结束 Shell。
- 文件传输支持最大 1 GiB 的普通文件。宿主目标限制在当前 Android 用户的共享存储（含 `/sdcard` 别名）和 `/data/local/tmp`，并检查路径中的符号链接。成功完整传输后才替换目标。普通访问能否读写共享存储取决于 HaniyaX 的系统存储授权。
- `--timeout SECONDS` 位于子命令前，允许 1–3600 秒。默认 120 秒，传输和安装默认 300 秒，交互 Shell 和 logcat 默认 900 秒。人工审批另外限时 90 秒。CLI 的用法错误是 2，服务不可用是 3，超时是 124，传输异常是 125，权限拒绝是 126，取消是 130。执行成功发起时，宿主命令也可能自行返回这些数值，结合 stderr 判断。

## 审批和紧急关闭

手动确认需要批准每次请求。常规自动授权放行以应用身份执行的固定只读查询。完全授权进一步放行当前访问模式下的结构化读取，包括读取日志和允许路径内的文件。Shell 语法无法可靠判断最终会产生什么操作，因此除少量严格匹配并转为固定参数的只读示例外，命令脚本与交互 Shell 都需要在 Android 界面确认。文件覆盖、APK 安装和系统设置写入始终需要确认。交互 Shell 按会话审批，批准后可以在该会话内执行对应 UID 能运行的命令。

审批仅能在 Android 界面完成。设置页支持取消单个请求、查看近期执行状态和紧急关闭。工作区通知也有紧急关闭入口。关闭桥接会拒绝新操作、撤销待批准请求，并终止受桥接管理的执行及其子进程。已经提交给 Android 系统服务的变更可能已生效。

桥接 IPC 使用抽象 Unix socket，校验 Android 对端 UID 和每次 App 进程启动生成的随机凭证。宿主服务位于 PRoot 外部。允许 Root 时，每次执行都会验证实际的 Android UID 0。PRoot 工作区内的项目和 Agent 共用该凭证以及 HaniyaX 的 Android 应用 UID，因此这里的授权是针对整个工作区。在 Android Root 管理器中也可单独撤销 HaniyaX 的 `su` 授权。

## Agent quick reference

Run `haniyax host status` first. Its JSON reports the live Android bridge permission and the actual Android app UID. Regular Linux commands still run inside Debian PRoot. Use `haniyax host --help` to list the Android operations. If the CLI requests on-device approval, wait for the user rather than retrying or starting a different shell. The Linux root identity and the Android root identity are separate.
