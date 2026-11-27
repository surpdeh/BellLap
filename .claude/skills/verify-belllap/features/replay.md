# Replay mode

Status: **RP-1 and RP-2 implemented (F03)**. RP-3 and RP-4 need Replay mode (F24) and are **not implemented yet**. `:testing:harness:replay` replays the synthetic fixtures through the F14 bell cascade and the baseline flag and crossing detectors, which F16 and F18 will replace. Bell metrics are per bell lap; see [bell-detection](./bell-detection.md).

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
- **Tier 1 evidence:** `testing/harness/build/reports/` holds `metrics.json`, `contact-sheet.png` (race frames, crossing frames outlined red) and `spectrogram.png` (bell with distractors). Open both PNGs with the Read tool. `replay` exits 1 when a metric breaks `testing/thresholds.json`.
- **Inspect the fixtures:** `./gradlew :testing:fixtures:generateFixtures` writes WAVs, PNG sequences and `labels.json` to `testing/fixtures/generated/`.
- **RP-3, RP-4:** report as **inconclusive: not implemented** until F24.
- **Tier 2 (RP-3, RP-4):** the CI emulator job pulls `events.jsonl` from `files/logs/events.jsonl` in the app sandbox and posts it in the evidence comment. Read the sequence there.

## Gotchas

- Generated fixtures are gitignored (`testing/fixtures/generated/`). Commit the generator, not the output.
- Never put real race footage in the repo.
