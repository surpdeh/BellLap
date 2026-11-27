#!/usr/bin/env bash
# Cuts a bell clip out of a local recording and lays it out as a real fixture (F03b layout, labels.json v1)
# under testing/fixtures/real/<name>/, which is gitignored. Never commit the output: see docs/FIXTURES.md.
# Usage: scripts/fixtures/clip-bell.sh INPUT START_S DURATION_S NAME [STRIKE_S ...]
#   STRIKE_S: bell strike times in seconds from the start of the clip (ground truth, by ear or by waveform).
#   With none given, the script prints candidate onsets (energy rises above 1.5 kHz) to label by hand.
set -euo pipefail
[ $# -ge 4 ] || { sed -n '2,7p' "$0"; exit 2; }
IN=$1 START=$2 DUR=$3 NAME=$4; shift 4
cd "$(dirname "$0")/../.."
OUT="testing/fixtures/real/$NAME"; mkdir -p "$OUT"
ffmpeg -loglevel error -y -ss "$START" -t "$DUR" -i "$IN" -vn -ac 1 -ar 16000 -c:a pcm_s16le "$OUT/$NAME.wav"
if [ $# -eq 0 ]; then
  echo "No strike times given. Candidate onsets (s) from a 1.5 kHz high-pass energy rise:"
  ffmpeg -hide_banner -nostats -i "$OUT/$NAME.wav" -af "highpass=f=1500,silencedetect=n=-30dB:d=0.05" -f null - 2>&1 \
    | sed -n 's/.*silence_end: \([0-9.]*\).*/  \1/p'
  echo "Rerun with the strike times to write labels.json."
  exit 0
fi
ONSETS=$(for s in "$@"; do printf '%.0f\n' "$(echo "$s * 1000000000" | bc -l)"; done | sort -n | paste -sd, -)
cat > "$OUT/labels.json" <<JSON
{
  "schemaVersion": 1,
  "name": "$NAME",
  "audioFile": "$NAME.wav",
  "sampleRate": 16000,
  "bellOnsetsNs": [$ONSETS]
}
JSON
echo "Wrote $OUT/$NAME.wav and $OUT/labels.json. Run: ./gradlew :testing:harness:replayReal"
