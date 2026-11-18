#!/usr/bin/env bash
# HaniyaX — DeepSeek Harness (DSH) installer / updater. Runs inside the Debian workspace.
#
#   dsh.sh install [version]    Node.js 24 (if needed) + @deepseek-ai/dsh + the mobile front-end plugin (if bundled)
#   dsh.sh update  [version]    user-initiated; refuses versions the installed mobile plugin is not compatible with
#   dsh.sh plugin               (re)install the bundled mobile front-end plugin into the web profile
#   dsh.sh versions             prints installed / latest versions as JSON
#
# Output protocol: ::step:: / ::progress:: / ::error:: / ::result:: (see runtime/Jobs.kt).
# Never touches $DSH_HOME sessions, credentials or settings.
set -o pipefail
export LC_ALL=C.UTF-8
export PATH="/opt/node/bin:/opt/haniyax/bin:$HOME/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

NODE_MIN="24.9.0"
NODE_DIR="/opt/node"
PKG="@deepseek-ai/dsh"
PLUGIN_TGZ="/opt/haniyax/dsh/dsh-mobile.tgz"
WORK="$HOME/.cache/haniyax/dsh"
LOG="$WORK/last.log"

step()     { printf '::step:: %s\n' "$*"; }
progress() { printf '::progress:: %s\n' "$1"; }
result()   { printf '::result:: %s\n' "$*"; }
fail()     { printf '::error:: %s\n' "$*"; exit 1; }

last_output() { [ -f "$LOG" ] && sed -e 's/\x1b\[[0-9;?]*[A-Za-z]//g' "$LOG" | grep -v '^\s*$' | tail -n 2 | tr '\n' ' ' | cut -c1-300; }

# run <description> <cmd...>: runs with output tee'd to the log; on failure explains why.
run() {
  local what="$1"; shift
  mkdir -p "$WORK"
  if ! "$@" 2>&1 | tee "$LOG"; then
    local out; out="$(last_output)"
    case "$out" in
      *"Could not resolve"*|*"ENOTFOUND"*|*"EAI_AGAIN"*|*"Network is unreachable"*|*"Failed to connect"*)
        fail "网络无法连接（$what）。请检查网络后重试。" ;;
      *"No space left"*|*"ENOSPC"*)
        fail "存储空间不足（$what）。请清理手机空间后重试。" ;;
      *) fail "$what 失败：${out:-没有输出}" ;;
    esac
  fi
}

ver_ge() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]; }

node_version() { command -v node >/dev/null 2>&1 && node --version 2>/dev/null | sed 's/^v//'; }

ensure_tools() {
  for t in curl tar xz sha256sum; do
    command -v "$t" >/dev/null 2>&1 || fail "缺少 $t。请在设置中重置工作区，或运行 apt-get install -y curl xz-utils。"
  done
}

ensure_node() {
  step "准备 Node.js 24"
  local have; have="$(node_version)"
  if [ -n "$have" ] && ver_ge "$have" "$NODE_MIN"; then
    echo "已有 Node.js v$have"
    progress 100
    return
  fi
  case "$(uname -m)" in aarch64|arm64) arch=arm64 ;; x86_64) arch=x64 ;; *) fail "不支持的架构：$(uname -m)" ;; esac
  local base="https://nodejs.org/dist/latest-v24.x"
  mkdir -p "$WORK"
  run "获取 Node.js 版本列表" curl -fsSL --retry 3 "$base/SHASUMS256.txt" -o "$WORK/SHASUMS256.txt"
  local file; file="$(grep -oE "node-v[0-9.]+-linux-$arch\.tar\.xz" "$WORK/SHASUMS256.txt" | head -n1)"
  [ -n "$file" ] || fail "没有找到适用于 linux-$arch 的 Node.js 24。"
  echo "下载 $file"
  progress 10
  run "下载 Node.js" curl -fL --retry 3 --progress-bar "$base/$file" -o "$WORK/$file"
  progress 70
  (cd "$WORK" && grep " $file\$" SHASUMS256.txt | sha256sum -c -) >/dev/null 2>&1 || fail "Node.js 下载文件校验失败，请重试。"
  rm -rf "$NODE_DIR.new" && mkdir -p "$NODE_DIR.new"
  run "解压 Node.js" tar -xJf "$WORK/$file" -C "$NODE_DIR.new" --strip-components=1
  rm -rf "$NODE_DIR.old"; [ -d "$NODE_DIR" ] && mv "$NODE_DIR" "$NODE_DIR.old"
  mv "$NODE_DIR.new" "$NODE_DIR" && rm -rf "$NODE_DIR.old" "$WORK/$file"
  echo "Node.js $(node --version)"
  progress 100
}

dsh_version() {
  local pj; pj="$(npm root -g 2>/dev/null)/$PKG/package.json"
  [ -f "$pj" ] && sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$pj" | head -n1
}

plugin_range() {
  # engines.dsh of the bundled (or installed) mobile plugin; empty if none.
  [ -f "$PLUGIN_TGZ" ] || return 0
  tar -xzOf "$PLUGIN_TGZ" package/package.json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const p=JSON.parse(s);process.stdout.write((p.engines&&p.engines.dsh)||"")}catch{}})'
}

# satisfies <version> <range>: uses npm's bundled semver.
satisfies() {
  local semver; semver="$(npm root -g)/npm/node_modules/semver"
  node -e 'const s=require(process.argv[1]);process.exit(s.satisfies(process.argv[2],process.argv[3],{includePrerelease:true})?0:1)' "$semver" "$1" "$2"
}

install_dsh() {
  local want="${1:-latest}"
  step "安装 DeepSeek Harness"
  if [ "$want" = latest ]; then
    want="$(npm view "$PKG" version 2>/dev/null)" || true
    [ -n "$want" ] || fail "无法从 npm 获取 DeepSeek Harness 的版本信息，请检查网络。"
  fi
  local range; range="$(plugin_range)"
  if [ -n "$range" ] && ! satisfies "$want" "$range"; then
    fail "DeepSeek Harness $want 与当前移动前端（要求 $range）不兼容，已取消。请等待移动前端更新后再升级。"
  fi
  echo "安装 $PKG@$want"
  progress 20
  run "安装 DeepSeek Harness" npm install -g --no-fund --no-audit "$PKG@$want"
  progress 100
}

install_plugin() {
  step "安装移动前端插件"
  if [ ! -f "$PLUGIN_TGZ" ]; then
    echo "此版本的 HaniyaX 未附带移动前端插件，将使用 DeepSeek Harness 自带界面。"
    progress 100
    return
  fi
  local range; range="$(plugin_range)"
  local have; have="$(dsh_version)"
  if [ -n "$range" ] && [ -n "$have" ] && ! satisfies "$have" "$range"; then
    fail "移动前端要求 DeepSeek Harness $range，当前为 $have。请先更新 DeepSeek Harness。"
  fi
  run "安装移动前端插件" dsh plugin --profile web add "$PLUGIN_TGZ"
  progress 100
}

versions() {
  local dv nv lv pv
  dv="$(dsh_version)"; nv="$(node_version)"
  lv="$(timeout 20 npm view "$PKG" version 2>/dev/null)"
  pv=""; [ -f "$PLUGIN_TGZ" ] && pv="$(tar -xzOf "$PLUGIN_TGZ" package/package.json 2>/dev/null | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
  printf '{"dsh":"%s","node":"%s","latest":"%s","plugin":"%s","pluginRange":"%s"}\n' "$dv" "$nv" "$lv" "$pv" "$(plugin_range 2>/dev/null)"
}

cmd="${1:-install}"; shift || true
case "$cmd" in
  install)
    ensure_tools; ensure_node; install_dsh "${1:-latest}"; install_plugin
    result "DeepSeek Harness $(dsh_version)" ;;
  update)
    ensure_tools; ensure_node; install_dsh "${1:-latest}"
    result "DeepSeek Harness $(dsh_version)" ;;
  plugin)
    install_plugin; result "ok" ;;
  versions)
    versions ;;
  *) echo "用法：dsh.sh install|update [版本]|plugin|versions" >&2; exit 2 ;;
esac
