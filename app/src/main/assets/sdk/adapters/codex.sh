#!/usr/bin/env bash
# HaniyaX adapter: Codex (OpenAI).
#   codex.sh install [version] | update [version] | latest | version
#
# Source: GitHub releases of openai/codex, tag rust-v<version> (verified 2026-11-15, rust-v0.169.0).
# Asset: codex-package-aarch64-unknown-linux-musl.tar.gz — the official package layout used by Codex's own
# installer (bin/codex, bin/codex-code-mode-host, bin/codex-exec-server, codex-path/rg, codex-resources/…),
# checked against codex-package_SHA256SUMS from the same release.
# Layout here (never inside ~/.codex, which holds config, login and history):
#   ~/.local/share/haniyax/agents/codex/<version>/   unpacked package
#   ~/.local/bin/codex -> …/<version>/bin/codex     switched atomically (ln -sfn + mv -T)
# Running sessions keep the version they started with; old versions are pruned only when unused.
. "$(dirname "$0")/common.sh"

ID=codex
REPO="https://github.com/openai/codex"
TARGET="aarch64-unknown-linux-musl"
ASSET="codex-package-$TARGET.tar.gz"
SUMS="codex-package_SHA256SUMS"
ROOT="$HOME/.local/share/haniyax/agents/codex"
LINK="$HOME/.local/bin/codex"

latest() {
  local loc v
  loc="$(curl -sS -o /dev/null -m 30 -w '%{redirect_url}' "$REPO/releases/latest" 2>/dev/null)"
  v="${loc##*/tag/}"; v="${v#rust-v}"
  semver_ok "$v" && printf '%s\n' "$v"
}

# Removes versions other than the current and previous one, unless a running process uses them.
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
  local want="${1:-}" url sum actual dest stage prev got
  step "检查运行环境"
  need_arch
  need_disk 900
  need_net "$REPO" "github.com"
  new_work codex

  step "获取版本信息"
  if [ -z "$want" ]; then
    want="$(latest)"
    [ -n "$want" ] || fail "无法从 GitHub 获取 Codex 最新版本号，请稍后重试。"
  fi
  semver_ok "$want" || fail "版本号格式不正确：$want"
  url="$REPO/releases/download/rust-v$want"
  log "目标版本：$want"

  step "下载 Codex $want"
  curl -fsSL --retry 2 -m 60 -o "$HX_TMP/$SUMS" "$url/$SUMS" >>"$HX_LOG" 2>&1 || {
    code=$?
    [ "$code" -eq 22 ] && fail "GitHub 上没有找到 Codex $want 的发布文件。"
    fail_cmd "$code" "下载校验文件"
  }
  sum="$(awk -v a="$ASSET" '$2==a || $2=="*"a {print $1}' "$HX_TMP/$SUMS" | head -n 1)"
  [ -n "$sum" ] || fail "架构不支持：Codex $want 没有提供 $TARGET 安装包。"
  download "$url/$ASSET" "$HX_TMP/$ASSET" || fail_cmd $? "下载 $ASSET"

  step "校验并解压"
  actual="$(sha256_of "$HX_TMP/$ASSET")"
  [ "$actual" = "$sum" ] || fail "下载的文件校验失败（SHA-256 不一致），请重试。"
  dest="$ROOT/$want"
  stage="$ROOT/.stage.$$"
  mkdir -p "$ROOT" "$(dirname "$LINK")" || fail "无法创建目录 $ROOT"
  rm -rf "$stage"; mkdir -p "$stage"
  tar -xzf "$HX_TMP/$ASSET" -C "$stage" >>"$HX_LOG" 2>&1 || {
    code=$?; rm -rf "$stage"
    need_disk 300
    fail_cmd "$code" "解压安装包"
  }
  rm -f "$HX_TMP/$ASSET"
  [ -x "$stage/bin/codex" ] || { rm -rf "$stage"; fail "安装包内容异常：缺少 bin/codex。"; }
  got="$(version_of "$stage/bin/codex")"
  [ -n "$got" ] || { rm -rf "$stage"; fail "Codex 无法在此环境运行（codex --version 无输出）。$(last_output)"; }

  step "切换到新版本"
  if [ -d "$dest" ] && [ "$(version_of "$dest/bin/codex")" = "$got" ]; then
    # Same version already unpacked (maybe in use by a running session): keep it untouched.
    rm -rf "$stage"
  elif [ -d "$dest" ]; then
    prev="$ROOT/.old.$$"
    mv -T "$dest" "$prev" || fail "无法替换已有的 $want 目录"
    mv -T "$stage" "$dest" || { mv -T "$prev" "$dest"; fail "无法写入 $dest"; }
    rm -rf "$prev"
  else
    mv -T "$stage" "$dest" || fail "无法写入 $dest"
  fi
  # A user's own binary at ~/.local/bin/codex is kept aside, not deleted.
  if [ -e "$LINK" ] && [ ! -L "$LINK" ]; then
    mv -f "$LINK" "$LINK.user-backup" && log "已将原有的 $LINK 保留为 $LINK.user-backup"
  fi
  prev="$(readlink "$LINK" 2>/dev/null)"; prev="${prev#"$ROOT/"}"; prev="${prev%%/*}"
  ln -sfn "$dest/bin/codex" "$LINK.hx-new.$$" && mv -Tf "$LINK.hx-new.$$" "$LINK" \
    || { rm -f "$LINK.hx-new.$$"; fail "无法更新 $LINK"; }

  step "校验安装"
  got="$(version_of "$LINK")"
  [ -n "$got" ] || fail "codex --version 没有返回版本号。"
  write_marker "$ID" "$got" "github-release"
  prune "$want" "$prev"
  log "已安装 Codex $got"
  result "$got"
}

case "${1:-}" in
  install) shift; do_install "${1:-}" ;;
  update)  shift; do_install "${1:-}" ;;
  latest)  latest || { printf '::error:: 无法获取最新版本号\n'; exit 1; } ;;
  version) version_of "$LINK" ;;
  *) printf '用法：%s install [版本] | update [版本] | latest | version\n' "$0" >&2; exit 2 ;;
esac
