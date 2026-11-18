#!/usr/bin/env bash
# HaniyaX adapter: OpenCode (Anomaly, v2).
#   opencode.sh install [version] | update [version] | latest | version
#
# Uses the official v2 installer https://opencode.ai/v2/install (verified 2026-11-15 against v2.3.0):
#   downloads @opencode/cli-linux-arm64 from the npm registry and moves the binary to ~/.opencode/bin/opencode
#   (a rename, so running sessions keep their binary). `--version <v>` pins a version, `--no-modify-path`
#   leaves shell profiles alone (HaniyaX already puts ~/.opencode/bin on PATH).
# Latest version: https://opencode.ai/update/api/latest/cli/npm (.version) — the installer's own source.
# Config (~/.config/opencode) and data (~/.local/share/opencode) are never touched.
. "$(dirname "$0")/common.sh"

ID=opencode
BIN="$HOME/.opencode/bin/opencode"
META="https://opencode.ai/update/api/latest/cli/npm"
export OPENCODE_DISABLE_AUTOUPDATE=1

latest() {
  local body v
  body="$(curl -fsSL -m 30 "$META" 2>/dev/null)" || return 1
  if command -v jq >/dev/null 2>&1; then
    v="$(printf '%s' "$body" | jq -r '.version // empty' 2>/dev/null)"
  else
    v="$(printf '%s' "$body" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')"
  fi
  semver_ok "$v" && printf '%s\n' "$v"
}

do_install() {
  local want="${1:-}" got
  step "检查运行环境"
  need_arch
  need_disk 500
  need_net "https://opencode.ai/v2/install" "opencode.ai"
  need_net "https://registry.npmjs.org/" "registry.npmjs.org"
  new_work opencode

  step "获取版本信息"
  if [ -z "$want" ]; then
    want="$(latest)"
    [ -n "$want" ] || fail "无法从 opencode.ai 获取最新版本号，请稍后重试。"
  fi
  semver_ok "$want" || fail "版本号格式不正确：$want"
  log "目标版本：$want"

  step "下载官方安装脚本"
  curl -fsSL --retry 2 -m 60 -o "$HX_TMP/install" "https://opencode.ai/v2/install" >>"$HX_LOG" 2>&1 \
    || fail_cmd $? "下载安装脚本"
  grep -q 'registry.npmjs.org' "$HX_TMP/install" || fail "下载到的安装脚本内容异常，请稍后重试。"

  step "下载并安装 OpenCode $want"
  run_logged bash "$HX_TMP/install" --version "$want" --no-modify-path </dev/null || fail_cmd $? "OpenCode 安装程序"

  step "校验安装"
  [ -x "$BIN" ] || fail "安装完成但没有找到 $BIN。$(last_output)"
  got="$(version_of "$BIN")"
  [ -n "$got" ] || fail "opencode --version 没有返回版本号，可能无法在此环境运行。$(last_output)"
  write_marker "$ID" "$got" "official-installer"
  log "已安装 OpenCode $got"
  result "$got"
}

case "${1:-}" in
  install) shift; do_install "${1:-}" ;;
  update)  shift; do_install "${1:-}" ;;
  latest)  latest || { printf '::error:: 无法获取最新版本号\n'; exit 1; } ;;
  version) version_of "$BIN" ;;
  *) printf '用法：%s install [版本] | update [版本] | latest | version\n' "$0" >&2; exit 2 ;;
esac
