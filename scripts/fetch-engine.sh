#!/usr/bin/env bash
# Fetch the real PRoot engine binaries for the signed APK payload.
#
# The engine must land in the APK's lib/<abi>/ so Android's package manager extracts it
# to /data/app/<pkg>/lib/<abi>/ (ApplicationInfo.nativeLibraryDir) — the only
# app-reachable location where SELinux allows execve() on Android 10+ (W^X policy, see
# docs/DECISIONS.md ADR-021). A copy under assets/ -> filesDir/native/ FAILS with
# "error=13, Permission denied".
#
# The payload goes in app/src/main/jniLibs/<abi>/. The old wrap.sh-style route
# (src/main/resources/lib/<abi>/) is no longer packaged into lib/<abi>/ by AGP, which
# produced an APK whose lib/arm64-v8a/ held only dependency .so files and no engine.
# jniLibs is the supported path and packages every *.so it finds — which is exactly
# what the payload is named now.
#
# EVERY staged file is named lib*.so, executables included. Packaging a file into
# lib/<abi>/ is not enough to get it onto the device: for a non-debuggable package the
# installer's NativeLibrariesIterator keeps only entries whose base name starts with
# "lib" and ends with ".so" (frameworks/base, libs/androidfw/ApkParsing.cpp,
# ValidLibraryPathLastSlash()). Anything else — `proot`, `loader`, `libtalloc.so.2`,
# whose ".2" suffix also fails the check — is packaged and then silently dropped, so
# the app reports "No PRoot engine found" on release builds while debug builds work.
# Renaming is safe: PRoot is exec'd by absolute path and its loader is pinned via
# $PROOT_LOADER. The SONAME/DT_NEEDED entries are patched to match the new file names
# so the bionic linker still resolves the deps.
#
# Pulls the Termux proot package .deb (bionic-linked PRoot + its static loader + the
# libtalloc/libandroid-shmem deps) and unpacks the right pieces. Works on Ubuntu CI
# (ar + tar). Override the URL with PROOT_DEB_URL for pinning / mirrors.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${1:-arm64-v8a}"
DEB_ARCH="${DEB_ARCH:-aarch64}"
TARGET_DIR="$ROOT/app/src/main/jniLibs/$ABI"
PROOT_VERSION="${PROOT_VERSION:-5.1.107.92}"
PROOT_DEB_URL="${PROOT_DEB_URL:-https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_${DEB_ARCH}.deb}"
# Optional pinning: set PROOT_DEB_SHA256 to the .deb's SHA-256 (from the Termux
# Packages index for this version) to fail on unexpected content.
PROOT_DEB_SHA256="${PROOT_DEB_SHA256:-}"

# Payload file names — these MUST match com.lenix.nativebridge.NativeSetup.
OUT_PROOT="libproot.so"
OUT_LOADER="libprootloader.so"
OUT_LOADER32="libprootloader32.so"
OUT_TALLOC="libtalloc.so"
OUT_SHMEM="libandroid-shmem.so"

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
copy_engine "$OUT_PROOT"    "$PREFIX/bin/proot"
copy_engine "$OUT_LOADER"   "$PREFIX/libexec/proot/loader"
copy_engine "$OUT_LOADER32" "$PREFIX/libexec/proot/loader32"
copy_engine "$OUT_TALLOC"   "$PREFIX/lib/libtalloc.so.2"
copy_engine "$OUT_SHMEM"    "$PREFIX/lib/libandroid-shmem.so"

# ---- ELF name fixups ----------------------------------------------------------
# libtalloc ships as libtalloc.so.2 with SONAME "libtalloc.so.2", and proot's
# DT_NEEDED points at that name. We ship it as libtalloc.so, so both strings have to
# be rewritten or the bionic linker fails with "library libtalloc.so.2 not found".
# patchelf is the clean route; fall back to a fixed-length in-place patch of the
# .dynstr entry ("libtalloc.so.2" -> "libtalloc.so\0\0") which keeps every offset
# stable and is therefore safe without a full ELF rewrite.
patch_soname_and_needed() {
  local file="$1" old="$2" new="$3"
  [ -f "$file" ] || return 0
  if command -v patchelf >/dev/null 2>&1; then
    if [ "$(basename "$file")" = "$OUT_TALLOC" ]; then
      patchelf --set-soname "$new" "$file" 2>/dev/null || true
    fi
    patchelf --replace-needed "$old" "$new" "$file" 2>/dev/null || true
    return 0
  fi
  # Fallback: byte-patch the string table in place. Only valid because
  # len(new) <= len(old); the remainder is NUL-padded so the string ends early.
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
if len(new) > len(old):
    raise SystemExit(f"cannot patch {old} -> {new}: new name is longer")
data = bytearray(open(path, 'rb').read())
needle = old.encode() + b"\0"
repl = new.encode() + b"\0" * (len(needle) - len(new))
n = data.count(needle)
if n:
    data = bytearray(bytes(data).replace(needle, repl))
    open(path, 'wb').write(data)
print(f"    patched {n} occurrence(s) of {old} -> {new} in {path.split('/')[-1]}")
PY
}

if [ -d "$STAGE" ]; then
  echo "Fixing ELF dependency names to match the payload file names ..."
  for f in "$STAGE"/*; do
    patch_soname_and_needed "$f" "libtalloc.so.2" "$OUT_TALLOC"
  done
fi

# Sanity checks: real ELF, correct machine.
proot_file="$STAGE/$OUT_PROOT"
if [ ! -f "$proot_file" ]; then
  echo "ERROR: proot was not extracted. Check the .deb path/version." >&2
  exit 1
fi
magic="$(od -An -tx1 -N4 "$proot_file" | tr -d ' \n')"
if [ "$magic" != "7f454c46" ]; then
  echo "ERROR: $proot_file is not an ELF (magic $magic). Refusing to ship it." >&2
  exit 1
fi
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

# Every staged name must survive Android's installer filter, or the file ships in the
# APK and never reaches the device. Catch that here rather than on a user's phone.
bad=0
for f in "$STAGE"/*; do
  base="$(basename "$f")"
  case "$base" in
    lib*.so) ;;
    *) echo "ERROR: '$base' is not named lib*.so — Android will not extract it." >&2
       bad=1 ;;
  esac
done
[ "$bad" -eq 0 ] || exit 1

# A leftover payload under the old names would keep shipping dead weight in the APK
# (and confuse the on-device diagnosis), so clear them out.
if [ -d "$TARGET_DIR" ]; then
  for stale in proot loader loader32 tini busybox libtalloc.so.2; do
    if [ -e "$TARGET_DIR/$stale" ]; then
      echo "  - removing stale payload $stale (never extracted by Android)"
      rm -f "$TARGET_DIR/$stale"
    fi
  done
fi

mkdir -p "$TARGET_DIR"
cp -f "$STAGE"/* "$TARGET_DIR/"
chmod 0755 "$TARGET_DIR"/*

echo "Done. Engine payload for $ABI:"
ls -la "$TARGET_DIR"
