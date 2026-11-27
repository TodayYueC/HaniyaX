#!/usr/bin/env bash
# Run inside HaniyaX's Debian terminal on a rooted physical Android device.
# Usage: bash tools/verify-android-host.sh [test.apk expected.package]
set -euo pipefail
if (( $# != 0 && $# != 2 )); then
    echo 'Usage: verify-android-host.sh [test.apk expected.package]' >&2
    exit 2
fi

host=(haniyax host)
root=(haniyax host --root)
scratch="$(mktemp -d)"
tag="haniyax_bridge_test_${BASHPID}_${RANDOM}"
remote="/data/local/tmp/${tag}.bin"
pushed=0
set_key=0
cleanup() {
    local code=$?
    trap - EXIT
    if (( set_key )); then
        "${root[@]}" settings delete global "$tag" </dev/null || code=1
    fi
    if (( pushed )); then
        "${host[@]}" root "rm -f -- '$remote'" </dev/null || code=1
    fi
    rm -rf -- "$scratch"
    exit "$code"
}
trap cleanup EXIT

echo 'Approve requested operations in the HaniyaX Android interface.'
status="$("${host[@]}" status)"
app_uid="$(printf '%s' "$status" | jq -er 'select(.mode == "root" and .root == "granted") | .appUid')"
[[ "$(id -u)" == 0 ]]
[[ "$app_uid" != 0 && "$("${host[@]}" shell 'id -u')" == "$app_uid" ]]
[[ "$("${host[@]}" root 'id -u')" == 0 ]]
sdk="$("${host[@]}" getprop ro.build.version.sdk)"
[[ "$sdk" =~ ^[0-9]+$ && "$sdk" == "$(printf '%s' "$status" | jq -r '.androidSdk')" ]]
"${root[@]}" apps | grep -Fx 'package:dev.haniya.x'

printf 'HaniyaX\0Android\377\n' > "$scratch/source.bin"
pushed=1
"${root[@]}" push "$scratch/source.bin" "$remote"
"${root[@]}" pull "$remote" "$scratch/destination.bin"
cmp "$scratch/source.bin" "$scratch/destination.bin"

[[ "$("${root[@]}" settings get global "$tag")" == null ]]
set_key=1
"${root[@]}" settings put global "$tag" "$tag"
[[ "$("${root[@]}" settings get global "$tag")" == "$tag" ]]
"${root[@]}" settings delete global "$tag"
[[ "$("${root[@]}" settings get global "$tag")" == null ]]
set_key=0

if (( $# == 2 )); then
    "${host[@]}" install "$1"
    "${root[@]}" apps | grep -Fx "package:$2"
fi

[[ "$(printf 'streamed\n' | "${host[@]}" shell 'read -r word; printf "%s" "$word"')" == streamed ]]
code=0
"${host[@]}" shell 'printf "stderr check\n" >&2; exit 37' 2>"$scratch/stderr" || code=$?
[[ "$code" == 37 ]]
grep -Fx 'stderr check' "$scratch/stderr"
"${host[@]}" root "rm -f -- '$remote'" </dev/null
pushed=0
echo 'Verified Android app UID, UID 0, system properties, package list, binary transfer, settings write/read/delete, stdin, stderr and exit status.'
