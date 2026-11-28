#!/usr/bin/env bash
# Tier 1 verification for BellLap in the cloud container. Run from anywhere.
# Usage: .claude/skills/verify-belllap/scripts/tier1.sh [--record-screenshots] [run-id]
# Runs every Gradle call CI runs, from scripts/ci/gradle-checks.txt, one step per line.
# Screenshots are verified against the committed goldens, so drift fails the run.
# --record-screenshots runs recordRoborazziDebug in place of verifyRoborazziDebug. Use it only when the
# change intentionally alters the UI, then commit the rewritten goldens with that change.
# Writes evidence to evidence/tier1/<run-id>/ (gitignored) and prints a summary.
# Exit code: 0 every step passed, 1 a step failed or was inconclusive. Every step is reported.
set -uo pipefail
cd "$(dirname "$0")/../../../.." || exit 1
# shellcheck source=SCRIPTDIR/../../../../scripts/ci/lib.sh
. scripts/ci/lib.sh
RECORD=0
if [ "${1:-}" = --record-screenshots ]; then RECORD=1; shift; fi
RUN_ID="${1:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT="evidence/tier1/$RUN_ID"; mkdir -p "$OUT"
declare -A RESULT
STEPS=()

if doctor; then
  echo DOCTOR_OK | tee "$OUT/doctor.txt"
else
  echo "DOCTOR_FAILED: android-36 platform or Gradle missing. Environment is wrong; do not work around it." | tee "$OUT/doctor.txt"; exit 1
fi

STEPS+=(parity)
if ci_parity >"$OUT/parity.log" 2>&1; then RESULT[parity]=PASS; else RESULT[parity]=FAIL; fi
cp "$OUT/parity.log" "$OUT/parity.summary"

for key in $(gradle_keys); do
  read -ra tasks <<<"$(gradle_tasks "$key")"
  if [ "$RECORD" -eq 1 ]; then tasks=("${tasks[@]/#verifyRoborazziDebug/recordRoborazziDebug}"); fi
  STEPS+=("$key")
  echo "$key: ${tasks[*]}" > "$OUT/$key.tasks"
  RESULT[$key]=$(gradle_step "$OUT/$key.log" "${tasks[@]}")
  failed=$(failed_tasks "$OUT/$key.log")
  { grep -E "^BUILD (SUCCESSFUL|FAILED)" "$OUT/$key.log" | tail -1; [ -n "$failed" ] && echo "failed tasks: $failed"; } > "$OUT/$key.summary"
done

# Collect evidence
mkdir -p "$OUT/reports"
find . -path ./evidence -prune -o \( -path '*/build/test-results/testDebugUnitTest/*.xml' -o -path '*/build/test-results/test/*.xml' \) -print | while read -r f; do cp "$f" "$OUT/reports/"; done
# Goldens are the current UI once verify passed or record rewrote them; compare images exist only on drift.
find . -path ./evidence -prune -o \( -path './app/src/test/screenshots/*.png' -o -path '*/build/outputs/roborazzi/*.png' \) -print | while read -r f; do cp "$f" "$OUT/"; done
cp app/build/outputs/apk/debug/app-debug.apk "$OUT/" 2>/dev/null || true
ls -l "$OUT/app-debug.apk" > "$OUT/apk.txt" 2>&1 || true
cat "$OUT"/reports/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | sed -E 's/.*name="([^"]*)".*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 tests=\2 skipped=\3 failures=\4 errors=\5/' > "$OUT/junit-summary.txt"

{
  echo "run: $RUN_ID"
  echo "screenshots: $([ "$RECORD" -eq 1 ] && echo "recorded (goldens rewritten, review git status)" || echo "verified against goldens")"
  for k in "${STEPS[@]}"; do echo "$k: ${RESULT[$k]} $(tr '\n' ' ' < "$OUT/$k.summary" 2>/dev/null)"; done
  echo "junit:"; sed 's/^/  /' "$OUT/junit-summary.txt"
  echo "screenshot files:"; find "$OUT" -maxdepth 1 -name '*.png' | sort | sed 's/^/  /'
  echo "apk: $(cat "$OUT/apk.txt")"
} | tee "$OUT/summary.txt"
for k in "${STEPS[@]}"; do [ "${RESULT[$k]}" = PASS ] || exit 1; done
