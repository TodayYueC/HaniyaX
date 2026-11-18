# HaniyaX 工作区开发指南（给智能体与人类）

你正运行在 **HaniyaX** 的 Linux 工作区里：Android 上的 proot + Debian（glibc），以 root 身份运行，`$HOME=/root`。
终端、智能体（Claude Code / Codex / OpenCode）、DeepSeek Harness 和后台服务共用这一套环境。

HaniyaX 的首页可以放**自定义卡片**（插件）。卡片是一个声明式 JSON 文件，由 App 用自己的界面渲染，自动跟随主题。
你通过 `hx` 命令行来查询、创建、预览、安装、修改和移除卡片，也可以登记后台服务。

## 最常用的流程（先读这一段）

用户说「首页加一张卡，显示我的开发服务器是否在线，点一下看最近 20 行日志」时：

```bash
hx plugin list                                   # 1. 看看已有哪些卡片（也许只需要修改现有的）
hx plugin new dev-server --template dev-server   # 2. 从模板创建（模板：blank / dev-server / git-status / disk）
hx plugin params dev-server port=5173 log=/root/projects/web/dev.log   # 3. 填写本机参数
$EDITOR ~/.haniyax/plugins/dev-server/plugin.json                     # 4. 需要时编辑清单
hx plugin validate dev-server                    # 5. 校验（会列出所有错误及其 JSON 路径）
hx plugin preview dev-server                     # 6. 用实时数据预览卡片的文字版本——无需看屏幕即可自检
```

完成后卡片会**自动出现在首页**（App 每 2 秒检查一次文件变化，`hx` 也会主动通知）。重开 App 后依然存在。

要修改已有卡片：直接编辑 `~/.haniyax/plugins/<id>/plugin.json`，再 `hx plugin validate` 与 `hx plugin preview`。
不同的智能体（今天 Codex、明天 Claude Code）改的都是同一个文件。

## 两类卡片（务必告诉用户）

| 类型 | 判定 | 行为 |
|---|---|---|
| **仅展示** | 只用 `port` / `http` / `file` / `process` 数据源，动作只有 `sheet` / `openUrl` | 立即可用 |
| **可执行命令** | 含 `command` 数据源，或 `command` / `terminal` / `service` 动作 | 卡片带「可执行命令」标记；**用户必须在 HaniyaX 里点「允许」后才会运行** |

- 允许记录保存在 App 的私有目录里，工作区内无法读写，也**没有任何命令可以替用户批准**。不要尝试绕过。
- 命令或参数变化后需要重新允许。创建了可执行命令的卡片后，请提醒用户：「回到 HaniyaX 首页，点卡片上的『需要确认命令』并允许」。
- 能用仅展示实现的需求，优先用仅展示。

## 后台服务

开发服务器这类长期运行的进程，用 `hx service` 登记后由 HaniyaX 托管（首页「后台服务与日志」里可看状态、地址、日志并启停）：

```bash
hx service add --name web --cwd /root/projects/web --port 5173 --cmd 'npm run dev'
hx service start web
hx service logs web -n 20
```

日志默认写到 `~/.haniyax/logs/<name>.log`——卡片的 `file` 数据源可以直接读它。详见 `services.md`。

## 文件与目录

| 路径 | 内容 |
|---|---|
| `~/.haniyax/plugins/<id>/plugin.json` | 卡片清单（可分享） |
| `~/.haniyax/plugins/<id>/local.json` | 本机参数值（项目路径、端口……），**导出时不包含** |
| `~/.haniyax/services/<name>.json` | 后台服务定义 |
| `~/.haniyax/logs/` | 服务日志 |
| `/opt/haniyax/docs/` | 本文档、`plugins.md`（清单完整参考）、`services.md`、`cookbook.md`（配方）、`plugin.schema.json` |
| `/opt/haniyax/examples/` | 模板 |

## 约定

- 凭据不要写进 `plugin.json`；需要密钥的命令请读取环境变量或用户自己的配置文件。
- 卡片文字用简体中文，简短平和；不要用「失败」「警告」之类的刺眼措辞描述普通状态。
- 图标只能用 `plugins.md` 列出的名称（手绘图标集），未知名称会显示为拼图图标。
- `hx --json …` 输出机器可读结果；退出码：0 成功 · 1 失败 · 2 用法错误 · 3 无法连接 HaniyaX · 4 找不到对象。
- `hx help` 列出全部命令。插件接口版本：`apiVersion: 1`（`hx version` 可查看 App 支持的版本）。
