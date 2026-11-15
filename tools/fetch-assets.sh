#!/usr/bin/env bash
# Fetches the binary assets bundled in the APK: proot (+libs) from the Termux
# package repository and a Debian trixie arm64 rootfs exported via podman.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/runtime"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
R=https://packages.termux.dev/apt/termux-main
curl -fsSL "$R/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"
fn() { awk -v p="$1" '$0=="Package: "p{f=1} f&&/^Filename:/{print $2;exit}' "$WORK/Packages"; }
for pkg in proot libtalloc libandroid-shmem; do
  mkdir -p "$WORK/$pkg"; curl -fsSL "$R/$(fn $pkg)" -o "$WORK/$pkg.deb"
  (cd "$WORK/$pkg" && ar x "../$pkg.deb" && tar xf data.tar.*)
done
U=data/data/com.termux/files/usr
mkdir -p "$WORK/rt/lib"
cp "$WORK/proot/$U/bin/proot" "$WORK/proot/$U/libexec/proot/loader" "$WORK/rt/"
cp -L "$WORK/libtalloc/$U/lib/libtalloc.so.2" "$WORK/libandroid-shmem/$U/lib/libandroid-shmem.so" "$WORK/rt/lib/"
tar -C "$WORK/rt" -cJf "$OUT/proot-aarch64.tar.xz" .
if [ ! -f "$OUT/debian-aarch64.tar.xz" ] || [ "${REFRESH_ROOTFS:-0}" = 1 ]; then
  IMG=docker.io/library/debian:trixie-slim
  podman pull -q --arch arm64 "$IMG" >/dev/null
  cid=$(podman create --arch arm64 "$IMG" /bin/true)
  podman export "$cid" | xz -T0 -9 > "$OUT/debian-aarch64.tar.xz"
  podman rm -f "$cid" >/dev/null
fi
ls -la "$OUT"
