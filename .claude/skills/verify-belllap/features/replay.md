# Replay mode

Status: **not implemented yet** (F03 fixtures and F24 replay). `:testing:harness:replay` exists but is a stub that only runs the harness unit tests.

Replay runs the whole pipeline (state machine and detectors) on a chosen MP4 and WAV instead of the camera and microphone. It is the main way to verify on the emulator.

## Sub-features

- `RP-1` synthetic fixtures generate deterministically from a seed.
- `RP-2` detector metrics on fixtures are written to `testing/harness/build/reports/metrics.json` and compared with `testing/thresholds.json`.
- `RP-3` a debug build replays `race_synth.mp4` and `.wav` end to end.
- `RP-4` the event log lists `BELL → ARMING → ARMED → FLAG → CROSSING×N → STOP` and the sidecar exists.

## How to get to it (user POV)

- A debug build offers a replay entry that takes a fixture. No such entry exists yet.

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **Tier 1 (RP-1, RP-2):** `./gradlew :testing:fixtures:test :testing:harness:replay`, then check `metrics.json` against `testing/thresholds.json`.
- **Today:** `replay` succeeds without producing metrics. Report RP-1..RP-4 as **inconclusive: not implemented**. A green `replay` task is not a pass.
- **Tier 2 (RP-3, RP-4):** the CI emulator job pulls `events.jsonl` from `files/logs/events.jsonl` in the app sandbox and posts it in the evidence comment. Read the sequence there.

## Gotchas

- Generated fixtures are gitignored (`testing/fixtures/generated/`). Commit the generator, not the output.
- Never put real race footage in the repo.
