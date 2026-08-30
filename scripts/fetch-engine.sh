#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT/app/src/main/assets/native/arm64-v8a"

mkdir -p "$TARGET_DIR"

echo "Checking arm64-v8a engine binaries in assets/native/arm64-v8a/..."

# Preinstall/fetch PRoot engine binary
URL="https://raw.githubusercontent.com/termux/proot-distro/master/proot"
if curl -sSL -f "$URL" -o "$TARGET_DIR/proot.tmp" 2>/dev/null; then
  mv "$TARGET_DIR/proot.tmp" "$TARGET_DIR/proot"
  chmod +x "$TARGET_DIR/proot"
  echo "Downloaded official proot engine binary."
else
  echo "Using preinstalled proot launcher asset."
fi

chmod +x "$TARGET_DIR/proot"
ls -la "$TARGET_DIR"
