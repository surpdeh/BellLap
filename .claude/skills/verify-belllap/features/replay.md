# Replay mode

Status: **RP-1 and RP-2 implemented (F03); RP-3 and RP-4 implemented (F24)**, with label-driven stub detectors until F14, F16 and F18. `:testing:harness:replay` replays the synthetic fixtures through baseline detectors; the real detectors replace them later.

Replay runs the whole pipeline (state machine and detectors) on a chosen MP4 and WAV instead of the camera and microphone. It is the main way to verify on the emulator.

## Sub-features

- `RP-1` synthetic fixtures generate deterministically from a seed.
- `RP-2` detector metrics on fixtures are written to `testing/harness/build/reports/metrics.json` and compared with `testing/thresholds.json`.
- `RP-3` a debug build replays `race_synth.mp4` and `.wav` end to end.
- `RP-4` the event log lists `BELL → ARMING → ARMED → FLAG → CROSSING×N → STOP` and the sidecar exists.

## How to get to it (user POV)

- A debug build shows a "Replay (debug)" button over the setup screen (`replay_open`). It opens the Replay screen: a fixture button (`replay_run_race_synth`), a fast or real-time switch, and SAF pickers for an MP4 and a WAV.

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **Tier 1 (RP-1, RP-2):** `./gradlew :testing:fixtures:test :testing:harness:replay`, then check `metrics.json` against `testing/thresholds.json`.
- **Tier 1 evidence:** `testing/harness/build/reports/` holds `metrics.json`, `contact-sheet.png` (race frames, crossing frames outlined red) and `spectrogram.png` (bell with distractors). Open both PNGs with the Read tool. `replay` exits 1 when a metric breaks `testing/thresholds.json`.
- **Inspect the fixtures:** `./gradlew :testing:fixtures:generateFixtures` writes WAVs, PNG sequences and `labels.json` to `testing/fixtures/generated/`.
- **RP-3, RP-4 wiring (tier 1):** `./gradlew :core:replay:test` replays an in-memory session and asserts the exact event sequence on PTS. The MediaCodec decode path runs only on the emulator, so report it as inconclusive until the CI evidence comment shows it.
- **Tier 2 (RP-3, RP-4):** `ReplayRaceSynthTest` encodes `race_synth` to MP4 on the device, replays it from the debug menu and asserts the sequence in `events.jsonl`. the CI emulator job pulls `events.jsonl` from `files/logs/events.jsonl` in the app sandbox and posts it in the evidence comment. Read the sequence there.

## Gotchas

- Generated fixtures are gitignored (`testing/fixtures/generated/`). Commit the generator, not the output.
- Never put real race footage in the repo.
