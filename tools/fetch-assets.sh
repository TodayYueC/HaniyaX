#!/usr/bin/env bash
# Fetches the binary assets bundled in the APK: proot (+libs) from the Termux
# package repository and a Debian trixie arm64 rootfs exported via podman.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# ARCH=aarch64 (default, release) or ARCH=x86_64 (debug-only, for x86_64 emulators).
ARCH="${ARCH:-aarch64}"
case "$ARCH" in
  aarch64) OUT="$ROOT/app/src/main/assets/runtime"; OCI_ARCH=arm64 ;;
  x86_64)  OUT="$ROOT/app/src/debug/assets/runtime"; OCI_ARCH=amd64 ;;
  *) echo "unsupported ARCH=$ARCH" >&2; exit 2 ;;
esac
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
R=https://packages.termux.dev/apt/termux-main
curl -fsSL "$R/dists/stable/main/binary-$ARCH/Packages" -o "$WORK/Packages"
fn() { awk -v p="$1" '$0=="Package: "p{f=1} f&&/^Filename:/{print $2;exit}' "$WORK/Packages"; }
for pkg in proot libtalloc libandroid-shmem; do
  mkdir -p "$WORK/$pkg"; curl -fsSL "$R/$(fn $pkg)" -o "$WORK/$pkg.deb"
  (cd "$WORK/$pkg" && ar x "../$pkg.deb" && tar xf data.tar.*)
done
U=data/data/com.termux/files/usr
mkdir -p "$WORK/rt/lib"
cp "$WORK/proot/$U/bin/proot" "$WORK/proot/$U/libexec/proot/loader" "$WORK/rt/"
cp -L "$WORK/libtalloc/$U/lib/libtalloc.so.2" "$WORK/libandroid-shmem/$U/lib/libandroid-shmem.so" "$WORK/rt/lib/"
# Termux builds carry RUNPATH=/data/data/com.termux/files/usr/lib. Rewrite it in place to $ORIGIN-relative paths
# (shorter string, NUL-padded, ELF layout unchanged) so the libraries are found without LD_LIBRARY_PATH —
# which ARM translation layers ignore and which must not leak into guest processes anyway.
python3 - "$WORK/rt" <<'PY'
import sys, re, pathlib
root = pathlib.Path(sys.argv[1])
old = b"/data/data/com.termux/files/usr/lib\x00"
for rel, new in (("proot", b"$ORIGIN/lib"), ("lib/libtalloc.so.2", b"$ORIGIN"), ("lib/libandroid-shmem.so", b"$ORIGIN")):
    p = root / rel
    data = p.read_bytes()
    hits = [m.start() for m in re.finditer(re.escape(old), data)]
    if len(hits) != 1:
        sys.exit(f"{rel}: expected exactly one RUNPATH string, found {len(hits)}")
    repl = new + b"\x00" * (len(old) - len(new))
    p.write_bytes(data[:hits[0]] + repl + data[hits[0] + len(old):])
    print(f"patched RUNPATH of {rel} -> {new.decode()}")
PY
tar -C "$WORK/rt" -cJf "$OUT/proot-$ARCH.tar.xz" .
if [ ! -f "$OUT/debian-$ARCH.tar.xz" ] || [ "${REFRESH_ROOTFS:-0}" = 1 ]; then
  IMG=docker.io/library/debian:trixie-slim
  podman pull -q --arch "$OCI_ARCH" "$IMG" >/dev/null
  cid=$(podman create --arch "$OCI_ARCH" "$IMG" /bin/true)
  podman export "$cid" | xz -T0 -9 > "$OUT/debian-$ARCH.tar.xz"
  podman rm -f "$cid" >/dev/null
fi
ls -la "$OUT"
