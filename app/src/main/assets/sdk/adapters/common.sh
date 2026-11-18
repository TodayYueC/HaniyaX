# HaniyaX agent adapters — shared helpers. Sourced by <id>.sh; not run directly.
#
# Output protocol (read by the app, see runtime/Jobs.kt):
#   ::step:: <label>   ::progress:: <0-100>   ::error:: <message>   ::result:: <text>
# Everything else is plain log output.
#
# Rules every adapter follows:
#   - installs/updates only touch program files and ~/.haniyax/agents/<id>.json;
#     never ~/.claude, ~/.claude.json, ~/.codex, ~/.config/opencode, ~/.local/share/opencode
#     (except what an agent's own official installer writes itself, e.g. its download cache).
#   - program switches are atomic (rename), so running sessions keep their binary.

set -o pipefail
export LC_ALL=C.UTF-8
export PATH="$HOME/.local/bin:$HOME/.opencode/bin:/opt/haniyax/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

HX_AGENTS_STATE="$HOME/.haniyax/agents"
HX_WORK="$HOME/.cache/haniyax/adapters"
HX_LOG=""

step()     { printf '::step:: %s\n' "$*"; }
progress() { printf '::progress:: %s\n' "$1"; }
result()   { printf '::result:: %s\n' "$*"; }
log()      { printf '%s\n' "$*"; }

# Last meaningful line(s) of the captured command output, for error messages.
last_output() {
  [ -n "$HX_LOG" ] && [ -f "$HX_LOG" ] || return 0
  sed -e 's/\x1b\[[0-9;?]*[A-Za-z]//g' -e 's/\r/\n/g' "$HX_LOG" 2>/dev/null \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | grep -v '^$' | grep -v '^[#=■･ -]*[0-9.]*%\?$' \
    | tail -n 2 | tr '\n' ' ' | cut -c1-300
}

# fail <message>: print a human-readable reason and stop.
fail() {
  printf '::error:: %s\n' "$*"
  cleanup_work
  exit 1
}

# fail_cmd <code> <what>: a command (installer, download…) returned non-zero.
fail_cmd() {
  local code="$1"; shift
  local tail; tail="$(last_output)"
  case "$code" in
    23) fail "磁盘空间不足或无法写入临时目录（$*）。请清理空间后重试。" ;;
    6|7|28|35|52|56) fail "网络无法连接（$*，curl 退出码 $code）。请检查网络或代理后重试。" ;;
    137) fail "$* 被系统结束（可能内存不足）。请关闭其他应用后重试。" ;;
  esac
  if [ -n "$tail" ]; then fail "安装脚本返回异常（$*，退出码 $code）：$tail"
  else fail "安装脚本返回异常（$*，退出码 $code）。"; fi
}

new_work() {
  mkdir -p "$HX_WORK" || fail "无法创建临时目录 $HX_WORK"
  HX_TMP="$(mktemp -d "$HX_WORK/$1.XXXXXX")" || fail "无法创建临时目录（磁盘空间不足？）"
  HX_LOG="$HX_TMP/output.log"
  : > "$HX_LOG"
  export TMPDIR="$HX_TMP"
}

cleanup_work() {
  if [ -n "${HX_TMP:-}" ] && [ -d "$HX_TMP" ]; then rm -rf "$HX_TMP"; fi
}

# run_logged <cmd…>: runs a command, streams its (de-colored) output to the log and remembers it.
run_logged() {
  "$@" 2>&1 | sed -u -e 's/\x1b\[[0-9;?]*[A-Za-z]//g' -e 's/\r$//' | tee -a "$HX_LOG"
  return "${PIPESTATUS[0]}"
}

# Workspace architecture: aarch64 on phones; x86_64 on x86 devices/emulators. Every agent ships both.
case "$(uname -m)" in
  aarch64|arm64) HX_ARCH=aarch64; HX_NPM_ARCH=arm64 ;;
  x86_64|amd64)  HX_ARCH=x86_64;  HX_NPM_ARCH=x64 ;;
  *)             HX_ARCH="";      HX_NPM_ARCH="" ;;
esac

need_arch() {
  [ -n "$HX_ARCH" ] || fail "架构不支持：检测到 $(uname -m)，智能体只提供 arm64 与 x86_64 版本。"
}

# need_disk <MB>: free space in $HOME.
need_disk() {
  local need="$1" avail
  avail="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print int($4/1024)}')"
  if [ -n "$avail" ] && [ "$avail" -lt "$need" ]; then
    fail "磁盘空间不足：需要约 ${need} MB，当前可用 ${avail} MB。请清理手机存储后重试。"
  fi
}

# need_net <url> <name>: the host must answer (any HTTP status).
need_net() {
  local url="$1" name="$2" code
  code="$(curl -sS -o /dev/null -w '%{http_code}' -m 20 -I "$url" 2>/dev/null)" || code=000
  if [ "$code" = "000" ]; then
    fail "网络无法连接到 $name。请检查网络、代理或 DNS 设置后重试。"
  fi
}

semver_ok() { printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; }

# download <url> <out> [label]: curl with ::progress:: from Content-Length.
download() {
  local url="$1" out="$2" total size pid code
  total="$(curl -fsSLI -m 30 "$url" 2>/dev/null | tr -d '\r' | awk 'tolower($1)=="content-length:" {v=$2} END {print v+0}')"
  if [ "${total:-0}" -le 1 ]; then
    # Signed redirect targets (GitHub release assets) often refuse HEAD: read the size from a 1-byte range request.
    total="$(curl -fsSL -m 30 -r 0-0 -o /dev/null -D - "$url" 2>/dev/null | tr -d '\r' \
      | awk 'tolower($1)=="content-range:" {n=split($3,a,"/"); v=a[n]} END {print v+0}')"
  fi
  curl -fSL --retry 2 --connect-timeout 20 -o "$out" "$url" >>"$HX_LOG" 2>&1 &
  pid=$!
  local tick=0
  while kill -0 "$pid" 2>/dev/null; do
    size="$(stat -c %s "$out" 2>/dev/null || echo 0)"
    if [ "${total:-0}" -gt 1 ]; then
      progress "$(( size * 100 / total ))"
      [ $((tick % 5)) -eq 0 ] && log "已下载 $(( size / 1048576 )) / $(( total / 1048576 )) MB"
    elif [ $((tick % 5)) -eq 0 ]; then
      log "已下载 $(( size / 1048576 )) MB"
    fi
    tick=$((tick + 1))
    sleep 1
  done
  wait "$pid"; code=$?
  [ "$code" -eq 0 ] && progress 100
  return "$code"
}

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

# write_marker <id> <version> <method>: records that HaniyaX installed this agent.
write_marker() {
  local id="$1" version="$2" method="$3" now tmp
  now="$(date +%s)000"
  mkdir -p "$HX_AGENTS_STATE" || return 0
  tmp="$HX_AGENTS_STATE/.$id.json.tmp"
  printf '{\n  "version": "%s",\n  "installedAt": %s,\n  "method": "%s"\n}\n' "$version" "$now" "$method" > "$tmp" \
    && mv -f "$tmp" "$HX_AGENTS_STATE/$id.json"
}

# Version printed by "<bin> --version" (first x.y.z), or empty.
version_of() {
  [ -x "$1" ] || { command -v "$1" >/dev/null 2>&1 || return 0; }
  timeout 60 "$1" --version </dev/null 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?' | head -n 1
}

trap cleanup_work EXIT
