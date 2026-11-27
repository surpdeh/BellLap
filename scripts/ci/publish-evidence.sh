#!/usr/bin/env bash
# Publishes PR verification evidence (PNG/GIF and device-capabilities JSON) to the ci-evidence branch and upserts a single PR comment.
# Required env: GH_TOKEN PR SHA BUILD_RESULT E2E_RESULT RUN_URL GITHUB_REPOSITORY
set -euo pipefail
MARKER="<!-- belllap-evidence -->"
DEST="pr-${PR}/${SHA:0:8}"
WORK=$(mktemp -d)

git config --global user.name "belllap-ci"
git config --global user.email "ci@users.noreply.github.com"
REMOTE="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
if git ls-remote --exit-code --heads "$REMOTE" ci-evidence >/dev/null 2>&1; then
  git clone --depth 1 --branch ci-evidence "$REMOTE" "$WORK/ev"
else
  git init -q "$WORK/ev" && git -C "$WORK/ev" checkout -q --orphan ci-evidence
  git -C "$WORK/ev" remote add origin "$REMOTE"
fi
mkdir -p "$WORK/ev/$DEST"
find artifacts -type f \( -name '*.png' -o -name '*.gif' \) -size -8M | head -40 | while read -r f; do
  cp "$f" "$WORK/ev/$DEST/$(echo "${f#artifacts/}" | tr '/' '_')"
done
# Small probe JSON (F25) is published too, because the workflow artifact zip is not reachable from every client.
find artifacts -type f -name 'device-capabilities*.json' -size -1M | head -10 | while read -r f; do
  cp "$f" "$WORK/ev/$DEST/$(echo "${f#artifacts/}" | tr '/' '_')"
done
IMAGES=$(cd "$WORK/ev/$DEST" && ls 2>/dev/null | grep -E '\.(png|gif)$' || true)
JSONS=$(cd "$WORK/ev/$DEST" && ls 2>/dev/null | grep -E '\.json$' || true)
if [ -n "$IMAGES$JSONS" ]; then
  git -C "$WORK/ev" add -A && git -C "$WORK/ev" commit -qm "evidence for PR #${PR} @ ${SHA:0:8}" && git -C "$WORK/ev" push -q origin ci-evidence
fi

RAW="https://github.com/${GITHUB_REPOSITORY}/blob/ci-evidence/${DEST}"
{
  echo "$MARKER"
  echo "## Verification evidence (${SHA:0:8})"
  echo
  echo "| Tier | Result |"
  echo "|---|---|"
  echo "| 1 build / unit / lint / screenshots | ${BUILD_RESULT} |"
  echo "| 2 emulator e2e | ${E2E_RESULT} |"
  echo
  METRICS=$(find artifacts -name metrics.json | head -1)
  if [ -n "$METRICS" ]; then echo "### Detector metrics"; echo '```json'; head -c 6000 "$METRICS"; echo; echo '```'; fi
  YAMNET=$(find artifacts -name metrics-yamnet.json | head -1)
  if [ -n "$YAMNET" ]; then
    echo "<details><summary>Bell metrics with YAMNet stage 1 (scripts/bell/replay-yamnet.sh)</summary>"
    echo; echo '```json'; grep -E '"(source|bellStage1|violations)"|bell\.' "$YAMNET" | head -c 6000; echo; echo '```'; echo "</details>"
  fi
  for j in $JSONS; do
    echo "<details><summary>${j} ([raw](${RAW}/${j}?raw=true))</summary>"
    echo; echo '```json'; head -c 12000 "$WORK/ev/$DEST/$j"; echo; echo '```'; echo "</details>"
  done
  if [ -n "$IMAGES" ]; then
    echo "### Screens"
    for i in $IMAGES; do echo "<details><summary>${i}</summary><img src=\"${RAW}/${i}?raw=true\" width=\"360\"></details>"; done
  fi
  echo
  echo "Full reports, APK and screen recording (MP4): [workflow run artifacts](${RUN_URL})"
} > "$WORK/body.md"

EXISTING=$(gh api "repos/${GITHUB_REPOSITORY}/issues/${PR}/comments" --paginate --jq ".[] | select(.body | startswith(\"${MARKER}\")) | .id" | head -1)
if [ -n "$EXISTING" ]; then
  gh api -X PATCH "repos/${GITHUB_REPOSITORY}/issues/comments/${EXISTING}" -F body=@"$WORK/body.md" >/dev/null
else
  gh api "repos/${GITHUB_REPOSITORY}/issues/${PR}/comments" -F body=@"$WORK/body.md" >/dev/null
fi
