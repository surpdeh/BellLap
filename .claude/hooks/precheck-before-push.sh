#!/usr/bin/env bash
# Claude Code PreToolUse hook on Bash: run scripts/precheck.sh before a `git push`, and block the push when it
# does not pass. Reads the hook input JSON on stdin. Any command that is not a git push is allowed untouched.
# Exit 0 allows the tool call; exit 2 blocks it and shows stderr to Claude.
# Skips the re-run when build/precheck/passed-head holds the current HEAD and the working tree is clean.
set -uo pipefail
input=$(cat)
cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null) || exit 0
# A `git push` that starts a command (also after &&, ;, |, a newline, or VAR=x), with git's global options
# (-C dir, -c k=v, --no-pager). Quoted strings are dropped first, so `git commit -m "push"` is not a push.
push_re='(^|[;&|(])[[:space:]]*([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*[[:space:]]+)*git([[:space:]]+(-C|-c)[[:space:]]+[^[:space:]]+|[[:space:]]+--[^[:space:]]+)*[[:space:]]+push([[:space:]]|$)'
unquoted=$(printf '%s' "$cmd" | sed -E "s/'[^']*'//g; s/\"([^\"\\\\]|\\\\.)*\"//g")
printf '%s\n' "$unquoted" | grep -qE "$push_re" || exit 0

root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$root" || exit 0
marker=build/precheck/passed-head
if [ -f "$marker" ] && [ "$(cat "$marker")" = "$(git rev-parse HEAD)" ] && [ -z "$(git status --porcelain)" ]; then
  exit 0
fi

out=$(scripts/precheck.sh 2>&1); rc=$?
[ "$rc" -eq 0 ] && exit 0
{
  if [ "$rc" -eq 2 ]; then
    echo "Push blocked: scripts/precheck.sh was inconclusive (a check could not run). Fix the cause and rerun it."
  else
    echo "Push blocked: scripts/precheck.sh failed. CI would fail the same way. Fix it, commit, and push again."
  fi
  echo "$out" | tail -n 20
} >&2
exit 2
