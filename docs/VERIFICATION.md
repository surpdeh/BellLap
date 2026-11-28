# BellLap verification plan

This plan applies from the first build. Its goal is that every PR, starting with F00, carries **inspectable proof that the feature works**, produced by a Claude cloud agent with no phone, no camera and no real race footage.

## Constraints

| Environment | Has | Lacks |
|---|---|---|
| Claude cloud container ("belllap" env) | JDK 25 (JDK 21 also present), Gradle, Android SDK 36 (setup script), 4 CPU, 15 GB RAM, Google Maven via `dl.google.com` | **KVM, so no emulator**; camera; mic; GPU |
| GitHub Actions `ubuntu-latest` | KVM, Android SDK, `android-emulator-runner` | Real camera, high-speed capture, real OEM behaviour |
| Physical phones (developer or Firebase Test Lab) | Everything | Automation by cloud agents (Test Lab comes later, F40) |

## Three tiers

### Tier 1: cloud container (the agent runs it before opening a PR)

```bash
./gradlew testDebugUnitTest            # JVM unit tests: state machine, DSP, vision, sidecar
./gradlew :testing:harness:replay      # detector metrics on synthetic fixtures -> build/reports/metrics.json
./gradlew recordRoborazziDebug         # Compose screenshots via Robolectric -> */build/outputs/roborazzi/*.png
./gradlew lintDebug assembleDebug      # lint + APK builds
```

- **Evidence:** the agent pastes the summary lines of each command into the PR's Verification section. Changed screens are shown with the Roborazzi PNGs, which the agent can view itself with the Read tool before claiming success.
- **Proves:** pure logic, detector behaviour on fixtures, UI layout and states with fake ViewModels, and that the build compiles.
- **Does not prove:** CameraX, MediaCodec, foreground services, permissions or audio routing. Report these as *inconclusive at tier 1*.

### Tier 2: CI emulator (automatic on every PR)

- **Job `emulator-e2e`** runs an API 34 ATD x86_64 emulator with `-camera-back emulated`. It runs:
  - `connectedDebugAndroidTest`, which covers UI Automator/Compose flows;
  - `BugReportFlowTest` (F22), which drives the report form, consent and status screens against the in-process `:testing:fakebackend` and asserts the issue is filed;
  - `ReviewPlayerTest` (F19), which opens the replay fixture MP4 in the review player, nudges a frame, jumps between finishers, pinch-zooms (and checks the zoom survives a step), edits marks and renames the recording;
  - the **Replay e2e**: fixture MP4/WAV goes through `VideoFileFrameSource`/`WavFileAudioSource`, the real service and the state machine, and the test asserts that the event log reads `BELL → ARMING → ARMED → FLAG → CROSSING×N → STOP` and that the MP4 and sidecar exist.
- **Captured:** `adb screenrecord` of the flow (converted to GIF), screenshots at each state, logcat, and the JSONL event log.
- **Published** to the `ci-evidence` branch, with one auto-updated PR comment that embeds them.
- **Proves:** Android wiring on a real OS: services, permissions (auto-granted), the Media3 player, the file pipeline, and the emulator camera preview.

### Tier 3: physical devices (developer field checklist, later Firebase Test Lab)

Run this checklist at each milestone and record the result in the milestone tracking issue:

- [ ] Standby for 30 minutes dimmed, then `Bell now`, then ARMED, then a camera preview appears.
- [ ] A real bell rung 3 m away is detected within 1.5 s.
- [ ] A chequered flag (printed A3) waved in view moves the app to ACQUISITION; the file is written; the app stands down after the timeout.
- [ ] Battery drain per hour in standby is recorded.
- [ ] A bug report is submitted from the device and the GitHub issue appears.

## Fixtures (no real footage needed on day one)

`:testing:fixtures` produces **synthetic, deterministic fixtures**, seeded and generated at build time:

| Fixture | Generated content | Labels |
|---|---|---|
| `bell_synth_*.wav` | Inharmonic decaying partials (handbell model) mixed with pink and crowd-like noise at SNRs of 0/5/10 dB, plus distractors (claps, voice-band noise, freewheel clicks) | Bell onset times |
| `flag_synth_*.mp4` / PNG seq | A warped, waving chequered texture composited on a road background, with motion blur, plus distractors (chequered banner, static) | Flag-visible intervals |
| `sprint_synth_*.mp4` / PNG seq | Rider silhouettes crossing a white vertical line, singly and in bunches | Crossing frame per rider |
| `race_synth.mp4` + `.wav` | A full timeline: ambience, bell, flag, N crossings, silence | The full event sequence |

**Real footage** (F03b) is added under the same label schema (`labels.json`).

- The golden set (<1–2 GB) goes in Git LFS once the repo is private.
- The larger set goes in a private bucket, fetched by a nightly job using a secret.
- **Threshold rule:** `testing/thresholds.json` holds a separate threshold for each fixture set. When metrics on synthetic fixtures regress, PR CI fails. Real-footage metrics are reported nightly and become gating once they are stable.

## PR evidence contract

Every feature PR has a **Verification** section, using the template in `.github/pull_request_template.md`:

1. What changed, and which tier proves each acceptance criterion.
2. The commands run, with their output (tier 1).
3. Screenshots of each changed screen (Roborazzi, and the emulator in CI).
4. A screen recording of the changed flow (CI emulator GIF/MP4), where the flow is user-visible.
5. Test and metric reports (CI comment).
6. Anything inconclusive, with the reason.

## Bootstrapping order

1. **F00:** the skeleton builds in the container, with one Roborazzi screenshot of the placeholder SETUP screen. This is the first evidence.
2. **F01:** CI runs tiers 1 and 2 and posts the evidence comment. The emulator screenshot of the launched app is the first tier 2 evidence.
3. **F02:** run `/create-verification-skill` to complete `.claude/skills/verify-belllap/`, then prove it end to end (launch, doctor, drive one feature, evidence, cleanup).
4. **F03 and F24:** synthetic fixtures and Replay mode. From here on, every detector or flow PR shows replay evidence.
