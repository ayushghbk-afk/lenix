#!/usr/bin/env bash
# Fetch the real PRoot engine binaries for the signed APK payload.
#
# The engine must live in app/src/main/resources/lib/<abi>/ so it lands in the APK's
# lib/<abi>/ and Android's package manager extracts it to /data/app/<pkg>/lib/<abi>/
# (ApplicationInfo.nativeLibraryDir) — the only app-reachable location where SELinux
# allows execve() on Android 10+ (W^X policy, see docs/DECISIONS.md ADR-021).
#
# Why resources/lib and not jniLibs: Android Studio/AGP only packages `*.so` files
# from jniLibs directories, while src/main/resources/lib/<abi>/ is the documented
# (wrap.sh) route for packaging arbitrary executables under APK lib/<abi>/.
# A copy under assets/ -> filesDir/native/ FAILS with "error=13, Permission denied".
#
# Pulls the Termux proot package .deb (bionic-linked PRoot + its static loader + the
# libtalloc/libandroid-shmem deps) and unpacks the right pieces. Works on Ubuntu CI
# (ar + tar). Override the URL with PROOT_DEB_URL for pinning / mirrors.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${1:-arm64-v8a}"
DEB_ARCH="${DEB_ARCH:-aarch64}"
TARGET_DIR="$ROOT/app/src/main/resources/lib/$ABI"
PROOT_VERSION="${PROOT_VERSION:-5.1.107.92}"
PROOT_DEB_URL="${PROOT_DEB_URL:-https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_${DEB_ARCH}.deb}"
# Optional pinning: set PROOT_DEB_SHA256 to the .deb's SHA-256 (from the Termux
# Packages index for this version) to fail on unexpected content.
PROOT_DEB_SHA256="${PROOT_DEB_SHA256:-}"

WORK="$(mktemp -d)"
STAGE="$WORK/stage"
trap 'rm -rf "$WORK"' EXIT

echo "Fetching PRoot ${PROOT_VERSION} (${DEB_ARCH}) from $PROOT_DEB_URL ..."
curl -fsSL "$PROOT_DEB_URL" -o "$WORK/proot.deb"

if [ -n "$PROOT_DEB_SHA256" ]; then
  actual="$(sha256sum "$WORK/proot.deb" | awk '{print $1}')"
  if [ "$actual" != "$PROOT_DEB_SHA256" ]; then
    echo "ERROR: proot .deb SHA-256 mismatch (got $actual, want $PROOT_DEB_SHA256)." >&2
    exit 1
  fi
  echo "  sha256 OK"
fi

# .deb = ar archive of cpio-less tarballs; no dpkg-deb needed.
# Note: GNU ar returns 0 even when the named member is absent, so probe for the file.
( cd "$WORK" && ar x proot.deb data.tar.xz ) >/dev/null 2>&1 || true
if [ ! -f "$WORK/data.tar.xz" ]; then
  ( cd "$WORK" && ar x proot.deb data.tar.gz ) >/dev/null 2>&1 || true
fi
if [ -f "$WORK/data.tar.xz" ]; then
  tar -xf "$WORK/data.tar.xz" -C "$WORK"
elif [ -f "$WORK/data.tar.gz" ]; then
  tar -xzf "$WORK/data.tar.gz" -C "$WORK"
else
  echo "ERROR: unsupported .deb payload layout." >&2
  exit 1
fi

USR_DIR="$(find "$WORK" -type d -path '*/files/usr' | head -n1)"
PREFIX="${USR_DIR:-$WORK/data/data/com.termux/files/usr}"

# Stage first: only copy into the payload directory once everything validated.
copy_engine() {
  local name="$1" src="$2"
  if [ ! -f "$src" ]; then
    echo "WARNING: $name not found in package ($src) — skipping." >&2
    return 0
  fi
  mkdir -p "$STAGE"
  cp "$src" "$STAGE/$name"
  chmod 0755 "$STAGE/$name"
  echo "  + $name ($(wc -c < "$STAGE/$name") bytes)"
}

echo "Extracting engine payload from the .deb ..."
copy_engine proot  "$PREFIX/bin/proot"
copy_engine loader "$PREFIX/libexec/proot/loader"
copy_engine loader32 "$PREFIX/libexec/proot/loader32"
copy_engine libtalloc.so.2 "$PREFIX/lib/libtalloc.so.2"
copy_engine libandroid-shmem.so "$PREFIX/lib/libandroid-shmem.so"

# Sanity checks: real ELF, correct machine.
proot_file="$STAGE/proot"
if [ ! -f "$proot_file" ]; then
  echo "ERROR: proot was not extracted. Check the .deb path/version." >&2
  exit 1
fi
magic="$(od -An -tx1 -N4 "$proot_file" | tr -d ' \n')"
if [ "$magic" != "7f454c46" ]; then
  echo "ERROR: $proot_file is not an ELF (magic $magic). Refusing to ship it." >&2
  exit 1
fi
machine="$(od -An -tu2 -j18 -N2 "$proot_file" | tr -d ' ')"
# `od -tu2` prints host byte order; parse little-endian explicitly for portability.
machine_hex="$(od -An -tx1 -j18 -N2 "$proot_file" | tr -d ' \n')"
machine=$(( 0x${machine_hex:2:2}${machine_hex:0:2} ))
case "$ABI" in
  arm64-v8a)      want_machine=183 ;;
  armeabi-v7a)    want_machine=40 ;;
  x86_64)         want_machine=62 ;;
  x86)            want_machine=3 ;;
  *) echo "ERROR: unknown ABI $ABI" >&2; exit 1 ;;
esac
if [ "$machine" != "$want_machine" ]; then
  echo "ERROR: proot e_machine=$machine does not match $ABI ($want_machine)." >&2
  exit 1
fi

mkdir -p "$TARGET_DIR"
cp -f "$STAGE"/* "$TARGET_DIR/"
chmod 0755 "$TARGET_DIR"/*

echo "Done. Engine payload for $ABI:"
ls -la "$TARGET_DIR"
