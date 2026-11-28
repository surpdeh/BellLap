#!/usr/bin/env bash
# Full bell cascade (stage 0 + YAMNet + persistence) on the synthetic fixtures, at tier 1 (F14).
# 1. replay with -Pbell.dump: every window stage 0 would show YAMNet is written to disk;
# 2. yamnet_score.py runs the real YAMNet .tflite on them (Python LiteRT, in a venv under build/);
# 3. replay with -Pbell.scores: the harness uses those scores and checks testing/thresholds.json.
# Usage: scripts/bell/replay-yamnet.sh   (needs python3 with venv, and network for pip and the model)
set -euo pipefail
cd "$(dirname "$0")/../.."
WORK=testing/harness/build/yamnet
mkdir -p "$WORK"
prop() { grep "^belllap.yamnet.$1=" gradle.properties | cut -d= -f2-; }
MODEL="$WORK/yamnet.tflite"
if ! echo "$(prop sha256)  $MODEL" | sha256sum -c --quiet 2>/dev/null; then
  curl -fsSL -o "$MODEL" "$(prop url)"
  echo "$(prop sha256)  $MODEL" | sha256sum -c --quiet
fi
if [ ! -x "$WORK/venv/bin/python" ]; then
  python3 -m venv "$WORK/venv"
  "$WORK/venv/bin/pip" install -q "ai-edge-litert==2.3.0" "numpy<3"
fi
rm -rf "$WORK/windows"
./gradlew :testing:harness:replay -Pbell.dump="$PWD/$WORK/windows" --console=plain -q
"$WORK/venv/bin/python" -I scripts/bell/yamnet_score.py "$MODEL" "$WORK/windows" "$WORK/scores.json" 2>&1 | grep -v "^INFO:"
./gradlew :testing:harness:replay -Pbell.scores="$PWD/$WORK/scores.json" --console=plain -q
