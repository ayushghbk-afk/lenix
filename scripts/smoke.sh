#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found; install Android platform-tools before running the smoke test." >&2
  exit 2
fi

./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lenix/com.lenix.ui.MainActivity

echo "Smoke test: launch command sent. Verify the Lenix home screen on the device."
