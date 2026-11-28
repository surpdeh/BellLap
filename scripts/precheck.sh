#!/usr/bin/env bash
# Fast pre-push check: CI parity, then detekt, lint and unit tests (about 1 min warm). No replay, no screenshots.
# Run it before every push; the Claude Code PreToolUse hook (.claude/hooks/precheck-before-push.sh) enforces it.
# Usage: scripts/precheck.sh
# Exit code: 0 pass, 1 a check failed, 2 a check could not run (inconclusive, never a pass).
# On a pass with a clean working tree, records HEAD in build/precheck/passed-head so the hook skips a re-run.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
# shellcheck source=SCRIPTDIR/ci/lib.sh
. scripts/ci/lib.sh
TASKS=(detekt lintDebug testDebugUnitTest)
DIR=build/precheck
MARKER="$DIR/passed-head"
mkdir -p "$DIR"
rm -f "$MARKER"
# The pass is only recorded for HEAD when HEAD is what was checked: a clean tree at the start.
head_at_start=""
[ -z "$(git status --porcelain)" ] && head_at_start=$(git rev-parse HEAD)

if ci_parity; then parity=PASS; else parity=FAIL; fi

if doctor; then
  gradle=$(gradle_step "$DIR/gradle.log" "${TASKS[@]}")
else
  echo "precheck: android-36 platform or Gradle missing, so the Gradle checks could not run" >&2
  gradle=INCONCLUSIVE
fi

echo "parity: $parity"
echo "${TASKS[*]}: $gradle $(failed_tasks "$DIR/gradle.log" 2>/dev/null)"
[ "$gradle" = PASS ] || echo "log: $DIR/gradle.log"

if [ "$parity" = FAIL ] || [ "$gradle" = FAIL ]; then echo "precheck: FAIL"; exit 1; fi
if [ "$gradle" = INCONCLUSIVE ]; then echo "precheck: INCONCLUSIVE (a check could not run; this is not a pass)"; exit 2; fi

if [ -n "$head_at_start" ] && [ "$head_at_start" = "$(git rev-parse HEAD)" ]; then
  echo "$head_at_start" > "$MARKER"
else
  echo "precheck: the working tree had uncommitted changes, so this pass is not recorded for HEAD"
fi
echo "precheck: PASS"
