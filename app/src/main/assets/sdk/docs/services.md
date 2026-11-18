# 后台服务

HaniyaX 托管长期运行的进程（开发服务器、DeepSeek Harness……），首页「后台服务与日志」显示状态、地址和日志，并可启停。

## 用 hx 管理

```bash
hx service add --name web --cwd /root/projects/web --port 5173 --cmd 'npm run dev' [--health /]
hx service list
hx service start web
hx service logs web -n 20
hx service stop web
hx service remove web
```

## 定义文件

`~/.haniyax/services/<name>.json`（`hx service add` 会帮你写）：

```json
{
  "name": "web",
  "command": "npm run dev",
  "cwd": "/root/projects/web",
  "port": 5173,
  "healthPath": "/",
  "env": { "NODE_ENV": "development" },
  "autostart": false,
  "description": "前端开发服务器"
}
```

| 字段 | 说明 |
|---|---|
| `command` | 以 `bash -lc` 运行 |
| `port` | 在 127.0.0.1 上监听的端口：用于健康检查与显示地址 |
| `healthPath` | 可选，HTTP 健康检查路径（状态码 < 500 视为健康） |
| `log` | 可选，日志文件；默认 `~/.haniyax/logs/<name>.log` |
| `autostart` | 工作区就绪后自动启动 |

## 生命周期

- 服务进程属于 HaniyaX 应用。离开页面、锁屏都不影响；**如果 Android 回收了应用，服务也会停止**，首页会如实显示「已停止」，点一下即可重新启动。开启 `autostart` 的服务会在下次打开 App 时自动启动。
- 端口被不由 HaniyaX 启动的程序占用时，会显示为「运行中 · 由其他程序启动」。
