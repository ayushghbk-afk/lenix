#!/bin/sh
#
# TEMPORARY DEBUG WRAPPER — REMOVE BEFORE MERGE.
#
# Runs the real Gradle wrapper (gradlew.real) and mirrors the tail of the build
# output into GitHub Actions `::error::` annotations, so a failure can be read
# from an environment that cannot download the run logs.
#
set -u

DIR=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
LOG=$(mktemp)
STATUS=$(mktemp)
CHUNKS=$(mktemp -d)

( "$DIR/gradlew.real" "$@" 2>&1; echo $? > "$STATUS" ) | tee "$LOG"
status=$(cat "$STATUS")

if [ -n "${GITHUB_ACTIONS:-}" ]; then
    {
        grep -n '^e: ' "$LOG" | head -n 40
        grep -n -A 12 '^\* What went wrong:' "$LOG" | head -n 40
        echo '--- last lines of build output ---'
        tail -n 60 "$LOG"
    } > "$CHUNKS/all.txt" 2>/dev/null

    split -l 10 "$CHUNKS/all.txt" "$CHUNKS/chunk_" 2>/dev/null

    for chunk in "$CHUNKS"/chunk_*; do
        [ -f "$chunk" ] || continue
        message=$(sed -e 's/%/%25/g' -e 's/\r/%0D/g' -e ':a' -e 'N' -e '$!ba' -e 's/\n/%0A/g' "$chunk")
        printf '::error::%s\n' "$message"
    done
fi

rm -rf "$CHUNKS" "$STATUS"
exit "$status"
