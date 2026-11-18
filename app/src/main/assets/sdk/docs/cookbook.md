# 卡片配方

## 1. 开发服务器是否在线，点一下看最近 20 行日志（仅展示）

```bash
hx service add --name web --cwd /root/projects/web --port 5173 --cmd 'npm run dev'
hx service start web
hx plugin new dev-server --template dev-server
hx plugin params dev-server port=5173 log=/root/.haniyax/logs/web.log
hx plugin validate dev-server && hx plugin preview dev-server
```

模板内容（`/opt/haniyax/examples/dev-server/plugin.json`）的要点：

```json
"sources": {
  "web": { "type": "port", "port": "{{params.port}}" },
  "log": { "type": "file", "path": "{{params.log}}", "tail": 20 }
},
"card": { "title": "开发服务器", "subtitle": "127.0.0.1:{{params.port}}",
          "blocks": [ { "type": "status", "source": "web", "ok": "在线", "fail": "未在运行" } ] },
"actions": [
  { "id": "logs", "label": "最近日志", "icon": "list", "type": "sheet", "title": "最近 20 行日志",
    "blocks": [ { "type": "log", "source": "log", "lines": 20 } ] }
],
"tap": "logs"
```

## 2. 加一个「重启」按钮（可执行命令，需要用户允许）

在上面的 `actions` 里加：

```json
{ "id": "restart", "label": "重启", "icon": "refresh", "type": "service", "name": "web", "op": "restart" }
```

保存后卡片会显示「可执行命令」并等待用户在 App 里允许。

## 3. Git 状态（可执行命令）

```bash
hx plugin new repo-status --template git-status
hx plugin params repo-status repo=/root/projects/web
```

使用 `command` 数据源运行 `git -C <repo> status --porcelain=v1 -b`。

## 4. 磁盘占用（可执行命令）

```bash
hx plugin new disk --template disk
hx plugin params disk dir=/root/projects
```

使用 `command` 数据源运行 `df` / `du`，需要用户在 App 中允许。

## 5. HTTP 健康检查与 JSON 字段

```json
"sources": { "health": { "type": "http", "url": "http://127.0.0.1:8080/health", "jsonPath": "version" } },
"card": { "blocks": [
  { "type": "status", "source": "health", "ok": "健康", "fail": "无响应" },
  { "type": "kv", "items": [ { "label": "版本", "value": "{{sources.health.value}}" } ] }
] }
```

## 6. 进程是否在跑

```json
"sources": { "worker": { "type": "process", "match": "celery worker" } },
"card": { "blocks": [ { "type": "status", "source": "worker", "ok": "运行中", "fail": "未运行" } ] }
```

## 修改已有卡片

1. `hx plugin show <id>` 看当前清单与参数；
2. 编辑 `~/.haniyax/plugins/<id>/plugin.json`，并把 `version` 加一；
3. `hx plugin validate <id>` → `hx plugin preview <id>`。
