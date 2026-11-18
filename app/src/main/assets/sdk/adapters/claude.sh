#!/usr/bin/env bash
# HaniyaX adapter: Claude Code (Anthropic).
#   claude.sh install [version] | update [version] | latest | version
#
# Uses the official native installer (https://claude.ai/install.sh, verified 2026-11-15):
#   launcher ~/.local/bin/claude -> ~/.local/share/claude/versions/<version>
#   `bash install.sh <version>` pins a version; the installer verifies the SHA-256 from the release manifest.
# The installer switches versions by re-pointing the launcher, so running sessions keep their binary.
# DISABLE_AUTOUPDATER=1 (documented env var) stops background self-updates; HaniyaX passes it on every launch,
# so updates stay user-decided without editing ~/.claude/settings.json.
# Settings (~/.claude, ~/.claude.json), login and history are never modified by this script; the official
# installer itself only writes its download cache (~/.claude/downloads) and the launcher.
. "$(dirname "$0")/common.sh"

ID=claude
BASE="https://downloads.claude.ai/claude-code-releases"
BIN="$HOME/.local/bin/claude"
export DISABLE_AUTOUPDATER=1

latest() {
  local v
  v="$(curl -fsSL -m 30 "$BASE/latest" 2>/dev/null | tr -d '[:space:]')"
  semver_ok "$v" && printf '%s\n' "$v"
}

do_install() {
  local want="${1:-}" got
  step "检查运行环境"
  need_arch
  need_disk 700
  need_net "$BASE/latest" "downloads.claude.ai"
  need_net "https://claude.ai/install.sh" "claude.ai"
  new_work claude

  step "获取版本信息"
  if [ -z "$want" ]; then
    want="$(latest)"
    [ -n "$want" ] || fail "无法从 downloads.claude.ai 获取最新版本号（服务可能不可达，或所在地区不受支持）。"
  fi
  semver_ok "$want" || fail "版本号格式不正确：$want"
  log "目标版本：$want"

  step "下载官方安装脚本"
  curl -fsSL --retry 2 -m 60 -o "$HX_TMP/install.sh" "https://claude.ai/install.sh" >>"$HX_LOG" 2>&1 \
    || fail_cmd $? "下载 install.sh"
  grep -q 'downloads.claude.ai' "$HX_TMP/install.sh" || fail "下载到的 install.sh 内容异常，请稍后重试。"

  step "下载并安装 Claude Code $want"
  # Not under sudo; plain (fake) root inside proot is accepted by the installer.
  run_logged env -u SUDO_USER bash "$HX_TMP/install.sh" "$want" || fail_cmd $? "Claude Code 安装程序"

  step "校验安装"
  [ -x "$BIN" ] || fail "安装完成但没有找到 $BIN。$(last_output)"
  got="$(version_of "$BIN")"
  [ -n "$got" ] || fail "claude --version 没有返回版本号，安装可能不完整。$(last_output)"
  log "已安装 Claude Code $got"
  write_marker "$ID" "$got" "native-installer"
  result "$got"
}

case "${1:-}" in
  install) shift; do_install "${1:-}" ;;
  update)  shift; do_install "${1:-}" ;;
  latest)  latest || { printf '::error:: 无法获取最新版本号\n'; exit 1; } ;;
  version) version_of "$BIN" ;;
  *) printf '用法：%s install [版本] | update [版本] | latest | version\n' "$0" >&2; exit 2 ;;
esac
