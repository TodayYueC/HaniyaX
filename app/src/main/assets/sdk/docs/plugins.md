# 卡片清单参考（apiVersion 1）

清单文件：`~/.haniyax/plugins/<id>/plugin.json`。JSON Schema：`/opt/haniyax/docs/plugin.schema.json`。
未知字段会被报错（防止拼写错误悄悄失效），用 `hx plugin validate <id>` 查看全部问题。

## 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `apiVersion` | 是 | 固定为 `1` |
| `id` | 是 | 小写字母、数字、连字符（`dev-server`），必须与目录名相同 |
| `name` | 是 | 卡片名称 |
| `version` | 是 | 语义化版本，如 `1.0.0`；修改卡片时请递增 |
| `description` | 否 | 一句话说明 |
| `icon` | 否 | 图标名（见文末），默认 `puzzle` |
| `params` | 否 | 本机参数声明（见下） |
| `refresh` | 否 | 数据刷新间隔（秒），最小 5，默认 15；只在卡片可见时刷新 |
| `sources` | 否 | 数据源（名称 → 定义） |
| `card` | 是 | `{ "title"?, "subtitle"?, "blocks": [...] }` |
| `actions` | 否 | 动作列表 |
| `tap` | 否 | 点按卡片时执行的动作 `id` |

## 参数 `params`

```json
"params": {
  "port": { "type": "number", "label": "端口", "default": 5173, "description": "…" },
  "log":  { "type": "path",   "label": "日志文件", "default": "~/projects/web/dev.log" }
}
```

`type`：`string` · `number` · `path` · `boolean`。实际取值写在同目录的 `local.json`（`hx plugin params <id> 键=值`，或在 App 的卡片详情里填写）。
`local.json` 只属于本机，导出分享时不会带走——项目路径、端口这类个人信息都放这里。

## 模板语法

在字符串里引用参数与数据：

- `{{params.port}}`
- `{{sources.web}}`（等同 `.value`）
- `{{sources.名称.字段}}`，字段：`value` · `ok` · `stdout` · `stderr` · `lines` · `status` · `error` · `count`
- `{{sources.名称.json}}` / `{{sources.名称.json.a.b[0].c}}`

模板不执行任何代码。代入命令时，参数值会被自动按 shell 规则加引号，避免注入。

## 数据源 `sources`

| type | 字段 | 结果 |
|---|---|---|
| `port` | `port`，`host`（默认 `127.0.0.1`） | `ok` = 端口能否连上 |
| `http` | `url`（仅 `http://127.0.0.1` / `localhost`），`expectStatus`，`jsonPath` | `status`、`value`（正文或 jsonPath 取值）、`json` |
| `file` | `path`，`tail`（取最后 N 行），`maxBytes` | `value`、`lines`、`count` |
| `process` | `match`（`pgrep -f` 模式） | `ok`、`count` |
| `command` | `run`，`cwd`，`timeout`（秒），`parse`：`text`/`json`/`lines` | `stdout`、`stderr`、`status`、`json`、`lines`（**需要用户允许**） |

路径中的 `~` 指 `/root`。

## 展示块 `card.blocks`

| type | 字段 | 说明 |
|---|---|---|
| `status` | `source`，`ok`，`fail`，`okWhen` | 状态点 + 文字。`okWhen`：`ok`（默认）· `!ok` · `nonempty` · `empty` · `contains:文本` · `equals:文本` · `matches:正则` · `gt:数字` · `lt:数字` |
| `text` | `value`，`style`：`body`/`caption`/`mono` | 文本 |
| `kv` | `items: [{label, value}]` | 键值行 |
| `metric` | `label`，`value`，`unit` | 数字指标 |
| `log` | `source`，`lines` | 等宽日志片段 |
| `progress` | `value`（0–1），`label` | 进度条 |
| `sparkline` | `source`（每行一个数字），`label` | 迷你折线 |

## 动作 `actions`

共同字段：`id`（必填）、`label`（必填）、`icon`。

| type | 字段 | 类别 |
|---|---|---|
| `sheet` | `title`，`blocks` | 仅展示：底部弹层展示更多内容（例如最近 20 行日志） |
| `openUrl` | `url`（仅本机地址） | 仅展示 |
| `command` | `run`，`cwd`，`confirm`（执行前再确认），`showOutput`，`timeout` | 可执行命令 |
| `terminal` | `run`，`cwd` | 可执行命令：在新终端标签里运行 |
| `service` | `name`，`op`：`start`/`stop`/`restart` | 可执行命令：启停已登记的服务 |

## 信任与允许

见 `README.md`「两类卡片」。允许的范围是**代入参数后的全部命令文本**；任何一条变化都需要用户重新允许。

## 导出与导入

- `hx plugin export <id> [文件.hxcard]`：打包清单与附带文件，不含 `local.json`。
- `hx plugin install <目录或 .hxcard>`：安装；也可以在 App 首页「编辑 → 导入卡片」。
- 导入者需要填写自己的参数；可执行命令的卡片需要导入者自己允许。

## 版本策略

- `apiVersion` 只在不兼容变更时递增；新增字段不改变版本。App 会拒绝它不认识的 `apiVersion`，并在卡片上说明。
- 卡片自身的 `version` 由作者维护，修改后递增，方便用户识别。

## 可用图标

terminal folder bot server plug branch keyboard pin puzzle cpu diff paste history zap power warn home timer chart
spark user plus minus drop scale run moon check habit bowl pulse note bell layout chevR chevL chevU chevD close
eyeOff eye shrink expand refresh send arrow sliders cal camera image shield shieldAlert target db globe help info
clock play stop ruler lock list link qr search trash edit copy download upload drag pause flame heart logout key palette
