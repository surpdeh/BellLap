#!/usr/bin/env bash
# Tier 1 verification for BellLap in the cloud container. Run from anywhere.
# Usage: .claude/skills/verify-belllap/scripts/tier1.sh [run-id]
# Writes evidence to evidence/tier1/<run-id>/ (gitignored) and prints a summary.
# Exit code: 0 all steps passed, 1 a step failed. Every step is reported, none are skipped silently.
set -uo pipefail
cd "$(dirname "$0")/../../../.."
: "${ANDROID_HOME:=/opt/android-sdk}"; export ANDROID_HOME
RUN_ID="${1:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT="evidence/tier1/$RUN_ID"; mkdir -p "$OUT"
declare -A RESULT

step() { # name, gradle args...
  local name="$1"; shift
  local log="$OUT/$name.log" rc
  ./gradlew "$@" --console=plain --continue >"$log" 2>&1; rc=$?
  if [ $rc -ne 0 ] && grep -q "status code 429" "$log"; then
    echo "[$name] Maven returned 429, retrying once after 30 s" >&2; sleep 30
    ./gradlew "$@" --console=plain --continue >"$log" 2>&1; rc=$?
  fi
  RESULT[$name]=$([ $rc -eq 0 ] && echo PASS || echo "FAIL(rc=$rc)")
  grep -E "^BUILD (SUCCESSFUL|FAILED)" "$log" | tail -1 > "$OUT/$name.summary"
}

# Doctor
if ls "$ANDROID_HOME/platforms" | grep -q android-36 && ./gradlew help -q >/dev/null 2>&1; then
  echo DOCTOR_OK | tee "$OUT/doctor.txt"
else
  echo "DOCTOR_FAILED: android-36 platform or Gradle missing. Environment is wrong; do not work around it." | tee "$OUT/doctor.txt"; exit 1
fi

step unit      testDebugUnitTest
step replay    :testing:harness:replay
step roborazzi recordRoborazziDebug
step build     lintDebug assembleDebug

# Collect evidence
mkdir -p "$OUT/reports"
find . -path ./evidence -prune -o -path '*/build/test-results/testDebugUnitTest/*.xml' -print | while read -r f; do cp "$f" "$OUT/reports/"; done
find . -path ./evidence -prune -o -path '*/build/outputs/roborazzi/*.png' -print -o -path '*/build/intermediates/roborazzi/*.png' -print | while read -r f; do cp "$f" "$OUT/"; done
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/" 2>/dev/null || true
ls -l "$OUT/app-debug.apk" > "$OUT/apk.txt" 2>&1 || true
cat "$OUT"/reports/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | sed -E 's/.*name="([^"]*)".*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 tests=\2 skipped=\3 failures=\4 errors=\5/' > "$OUT/junit-summary.txt"

{
  echo "run: $RUN_ID"
  for k in unit replay roborazzi build; do echo "$k: ${RESULT[$k]} $(cat "$OUT/$k.summary" 2>/dev/null)"; done
  echo "junit:"; sed 's/^/  /' "$OUT/junit-summary.txt"
  echo "screenshots:"; ls "$OUT"/*.png 2>/dev/null | sed 's/^/  /'
  echo "apk: $(cat "$OUT/apk.txt")"
} | tee "$OUT/summary.txt"
for k in unit replay roborazzi build; do [ "${RESULT[$k]}" = PASS ] || exit 1; done
