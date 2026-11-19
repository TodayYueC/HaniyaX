#!/usr/bin/env bash
# HaniyaX adapter: Antigravity CLI (Google), command `agy`.
#   antigravity.sh install [version] | update [version] | latest | version
#
# Source: the release manifest used by the official installer https://antigravity.google/cli/install.sh
# (checked 2026-11-19, v1.5.12):
#   <base>/manifests/linux_<arm64|amd64>.json → {"version", "url" (tar.gz with the `antigravity` binary), "sha512"}
# The manifest only names the newest build, so a pinned version must equal it.
# The official installer refuses to run when ~/.local/bin/agy exists and then runs `agy install` (shell aliases);
# we do the same download + SHA-512 check ourselves so updates work and profiles stay untouched.
# Layout (never inside ~/.gemini, which holds settings, sign-in and conversations):
#   ~/.local/share/haniyax/agents/antigravity/<version>/agy
#   ~/.local/bin/agy -> …/<version>/agy     switched atomically
# AGY_CLI_DISABLE_AUTO_UPDATE=true turns off the CLI's background self-update (documented in its troubleshooting).
. "$(dirname "$0")/common.sh"

ID=antigravity
BASE="https://antigravity-cli-auto-updater-974169037036.us-central1.run.app"
case "${HX_ARCH:-aarch64}" in x86_64) PLAT=linux_amd64 ;; *) PLAT=linux_arm64 ;; esac
MANIFEST="$BASE/manifests/$PLAT.json"
ROOT="$HOME/.local/share/haniyax/agents/antigravity"
LINK="$HOME/.local/bin/agy"
export AGY_CLI_DISABLE_AUTO_UPDATE=true

field() { sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1; }

manifest() { curl -fsSL -m 30 "$MANIFEST" 2>/dev/null | tr -d '\n'; }

latest() {
  local v
  v="$(manifest | field version)"
  semver_ok "$v" && printf '%s\n' "$v"
}

prune() {
  local keep_a="$1" keep_b="$2" d in_use
  in_use="$(for e in /proc/[0-9]*/exe; do readlink "$e" 2>/dev/null; done | sort -u)"
  for d in "$ROOT"/*/; do
    d="${d%/}"
    [ -d "$d" ] || continue
    case "${d##*/}" in "$keep_a"|"$keep_b"|.*) continue ;; esac
    if printf '%s\n' "$in_use" | grep -q "^$d/"; then
      log "保留 ${d##*/}：仍有会话在使用"
      continue
    fi
    rm -rf "$d" && log "已清理旧版本 ${d##*/}"
  done
}

do_install() {
  local want="${1:-}" m version url sum actual dest stage prev got
  step "检查运行环境"
  need_arch
  need_disk 600
  need_net "$BASE/manifests/$PLAT.json" "antigravity.google"
  new_work antigravity

  step "获取版本信息"
  m="$(manifest)"
  version="$(printf '%s' "$m" | field version)"
  url="$(printf '%s' "$m" | field url)"
  sum="$(printf '%s' "$m" | field sha512)"
  semver_ok "$version" && [ -n "$url" ] && [ -n "$sum" ] \
    || fail "无法获取 Antigravity 的发布信息，请稍后重试。"
  if [ -n "$want" ] && [ "$want" != "$version" ]; then
    log "官方只提供最新版本 $version，忽略指定的 $want"
  fi
  log "目标版本：$version"

  step "下载 Antigravity $version"
  download "$url" "$HX_TMP/agy.tar.gz" || fail_cmd $? "下载 Antigravity"

  step "校验并解压"
  actual="$(sha512sum "$HX_TMP/agy.tar.gz" | awk '{print $1}')"
  [ "$actual" = "$sum" ] || fail "下载的文件校验失败（SHA-512 不一致），请重试。"
  dest="$ROOT/$version"
  stage="$ROOT/.stage.$$"
  mkdir -p "$ROOT" "$(dirname "$LINK")" || fail "无法创建目录 $ROOT"
  rm -rf "$stage"; mkdir -p "$stage"
  tar -xzf "$HX_TMP/agy.tar.gz" -C "$stage" antigravity >>"$HX_LOG" 2>&1 || {
    code=$?; rm -rf "$stage"; fail_cmd "$code" "解压安装包"
  }
  rm -f "$HX_TMP/agy.tar.gz"
  mv "$stage/antigravity" "$stage/agy" && chmod 755 "$stage/agy" \
    || { rm -rf "$stage"; fail "安装包内容异常：缺少 antigravity。"; }
  got="$(version_of "$stage/agy")"
  [ -n "$got" ] || { rm -rf "$stage"; fail "Antigravity 无法在此环境运行（agy --version 无输出）。$(last_output)"; }

  step "切换到新版本"
  if [ -d "$dest" ] && [ "$(version_of "$dest/agy")" = "$got" ]; then
    rm -rf "$stage"
  elif [ -d "$dest" ]; then
    prev="$ROOT/.old.$$"
    mv -T "$dest" "$prev" || fail "无法替换已有的 $version 目录"
    mv -T "$stage" "$dest" || { mv -T "$prev" "$dest"; fail "无法写入 $dest"; }
    rm -rf "$prev"
  else
    mv -T "$stage" "$dest" || fail "无法写入 $dest"
  fi
  if [ -e "$LINK" ] && [ ! -L "$LINK" ]; then
    mv -f "$LINK" "$LINK.user-backup" && log "已将原有的 $LINK 保留为 $LINK.user-backup"
  fi
  prev="$(readlink "$LINK" 2>/dev/null)"; prev="${prev#"$ROOT/"}"; prev="${prev%%/*}"
  ln -sfn "$dest/agy" "$LINK.hx-new.$$" && mv -Tf "$LINK.hx-new.$$" "$LINK" \
    || { rm -f "$LINK.hx-new.$$"; fail "无法更新 $LINK"; }

  step "校验安装"
  got="$(version_of "$LINK")"
  [ -n "$got" ] || fail "agy --version 没有返回版本号。"
  write_marker "$ID" "$got" "release-manifest"
  prune "$version" "$prev"
  log "已安装 Antigravity $got"
  result "$got"
}

case "${1:-}" in
  install) shift; do_install "${1:-}" ;;
  update)  shift; do_install "${1:-}" ;;
  latest)  latest || { printf '::error:: 无法获取最新版本号\n'; exit 1; } ;;
  version) version_of "$LINK" ;;
  *) printf '用法：%s install [版本] | update [版本] | latest | version\n' "$0" >&2; exit 2 ;;
esac
