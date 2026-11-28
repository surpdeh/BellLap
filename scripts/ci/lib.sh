# Shared helpers for ci.yml, tier1.sh and precheck.sh. Source it from the repository root.
# It only defines functions and sets no shell options, so sourcing it in CI changes nothing else.
# shellcheck shell=bash

BELLLAP_CI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# gradle_keys: print the keys of scripts/ci/gradle-checks.txt, one per line.
gradle_keys() {
  sed -n 's/^\([A-Za-z0-9_-]*\):.*/\1/p' "$BELLLAP_CI_DIR/gradle-checks.txt"
}

# gradle_tasks <key>: print the tasks on the <key> line. Fails when the line is missing or empty,
# so CI never runs a bare `./gradlew` (which would pass).
gradle_tasks() {
  local line
  line=$(sed -n "s/^$1:[[:space:]]*//p" "$BELLLAP_CI_DIR/gradle-checks.txt")
  if [ -z "$line" ]; then
    echo "gradle_tasks: no '$1:' line in scripts/ci/gradle-checks.txt" >&2
    return 1
  fi
  echo "$line"
}

# doctor: succeed when the Android SDK platform and Gradle are usable.
doctor() {
  : "${ANDROID_HOME:=/opt/android-sdk}"; export ANDROID_HOME
  [ -d "$ANDROID_HOME/platforms/android-36" ] && ./gradlew help -q >/dev/null 2>&1
}

# gradle_step <log> <gradle args...>: run one Gradle call with --continue, writing its output to <log>.
# Retries once after 30 s when Maven answers 429. Prints PASS, FAIL or INCONCLUSIVE.
# INCONCLUSIVE means the check could not run (network or dependency download), never that it passed.
gradle_step() {
  local log="$1" rc
  shift
  ./gradlew "$@" --console=plain --continue >"$log" 2>&1; rc=$?
  if [ "$rc" -ne 0 ] && grep -q "status code 429" "$log"; then
    echo "Maven returned 429, retrying once after 30 s" >&2
    sleep 30
    ./gradlew "$@" --console=plain --continue >"$log" 2>&1; rc=$?
  fi
  if [ "$rc" -eq 0 ]; then
    echo PASS
  elif grep -qE "status code 429|Could not (resolve|GET|HEAD|download)|UnknownHostException|SocketTimeoutException|Connection (refused|reset|timed out)" "$log"; then
    echo INCONCLUSIVE
  else
    echo FAIL
  fi
}

# failed_tasks <log>: print the Gradle tasks that failed in <log>, space separated.
failed_tasks() {
  sed -n 's/^> Task \([^ ]*\) FAILED$/\1/p' "$1" | tr '\n' ' '
}

# ci_parity: fail when .github/workflows/ci.yml and gradle-checks.txt disagree.
# Every ./gradlew call in ci.yml must take its tasks from gradle_tasks, and every key must be read by ci.yml.
ci_parity() {
  local ci="$BELLLAP_CI_DIR/../../.github/workflows/ci.yml" bad=0 tok key
  while read -r tok; do
    echo "ci_parity: ci.yml runs Gradle task '$tok' directly. List it in scripts/ci/gradle-checks.txt and read it with gradle_tasks." >&2
    bad=1
  done < <(grep -oE '\./gradlew( +[^ #]+)*' "$ci" | tr -s ' ' '\n' | grep -vE '^(\./gradlew|-.*|\$.*)$')
  for key in $(gradle_keys); do
    if ! grep -qE "gradle_tasks $key( |\)|$)" "$ci"; then
      echo "ci_parity: scripts/ci/gradle-checks.txt lists '$key' but ci.yml never runs it (no 'gradle_tasks $key')." >&2
      bad=1
    fi
  done
  [ "$bad" -eq 0 ] && echo "ci_parity: ci.yml and scripts/ci/gradle-checks.txt agree"
  return "$bad"
}
