---
name: verify-belllap
description: "Prove a BellLap (Android app) change works and collect evidence for the PR. Covers the cloud container (JVM tests, detector replay metrics, Roborazzi Compose screenshots, assembleDebug) and the CI emulator tier (replay e2e, screen recording). Use before opening or updating any BellLap PR, or when asked to verify, screenshot or demo a feature."
---

# Verify BellLap

BellLap is an Android app (Kotlin, Compose, CameraX). Its user surface is a phone screen, and the cloud container has **no KVM, so no emulator**. Verification is therefore tiered. Read `features/README.md` for the maintained map of what to prove per feature, and `docs/VERIFICATION.md` for the plan.

A step that cannot run, or a command that is still a stub, is **inconclusive**, never a pass. Say so in the PR.

## Surfaces

- **Tier 1, cloud container (you are here):** JVM tests, Robolectric + Roborazzi Compose screenshots, replay metrics, lint, `assembleDebug`.
- **Tier 2, GitHub Actions emulator (`.github/workflows/ci.yml`, `scripts/ci/emulator-e2e.sh`):** runs on the PR, you cannot run it. After pushing, read the PR evidence comment (screenshots, GIF, JUnit, `events.jsonl`) and fix failures.
- **Tier 3, physical phone:** the developer runs it. List in the PR what still needs one.

## Launch (tier 1)

There is no server to start. Launch means the toolchain is ready:

```bash
cd /home/user/BellLap
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
./gradlew --version     # ready = Gradle 8.x and Launcher JVM 21
```

If the SDK is missing, the belllap environment setup script should have installed it (`docs/PLAN.md` §5). If `dl.google.com` is refused, report that the environment is wrong. Do not work around it.

## Doctor

Read-only. Run it first, and again whenever anything looks off.

```bash
ls "$ANDROID_HOME/platforms" | grep android-36 && ./gradlew help -q >/dev/null && echo DOCTOR_OK
```

## Drive

One command runs the whole tier-1 set, collects evidence and prints a summary:

```bash
.claude/skills/verify-belllap/scripts/tier1.sh [run-id]    # about 2 min cold, under 30 s warm
```

It runs doctor, then `testDebugUnitTest`, `:testing:harness:replay`, `recordRoborazziDebug`, `lintDebug assembleDebug`, retries once on a Maven 429, and exits 1 if any step fails. For a narrower loop, run a single piece:

| Purpose | Command | Proof |
|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest` | `*/build/test-results/testDebugUnitTest/*.xml` (tests/failures counts) |
| One module | `./gradlew :core:statemachine:test --tests '*Foo*'` | same, per module |
| Detector metrics | `./gradlew :testing:harness:replay` | **Stub until F03**: it only runs the harness unit tests and writes no `metrics.json`. Report as inconclusive. |
| Screenshots | `./gradlew recordRoborazziDebug` | `app/src/test/screenshots/*.png`. **Open each with the Read tool and say what it shows.** |
| Build | `./gradlew lintDebug assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |

Roborazzi writes golden PNGs into the tracked directory `app/src/test/screenshots/`. After a run, `git status` should show a change only where the UI intentionally changed. If a screenshot changed, commit the new golden with the UI change that caused it.

**UI handles:** use `Modifier.testTag(...)` with tags declared in `app/src/main/kotlin/net/belllap/ui/TestTags.kt`, and `onNodeWithTag`. Never coordinates. Drive the composable or ViewModel through UI events, not test-only setters.

Pick the feature file under `features/` that matches the change, and follow its recipe. A proof that drives one convenient entry point is incomplete when the map lists others.

## Evidence

- `tier1.sh` writes `evidence/tier1/<run-id>/` (gitignored): `summary.txt`, per-step logs, JUnit XML, `junit-summary.txt`, the Roborazzi PNGs and the APK. Paste `summary.txt` into the PR template's **Verification** section.
- Proof standards: exercise the real user path; capture the action and the resulting state; check side effects (files written, log lines) as well as what is visible.
- Commit **no** evidence binaries. CI publishes tier 2 evidence to the `ci-evidence` branch and into one auto-updated PR comment. Roborazzi goldens are the only committed PNGs.
- Never commit real race footage or bell recordings (see `CLAUDE.md`).

## Cleanup

```bash
./gradlew --stop     # stops the Gradle daemons this session started
```

Cleanup keeps `evidence/` and the `build/` reports until the PR is opened. Never delete them as part of cleanup. Never kill processes by name.

## Gotchas

- `repo.maven.apache.org` can answer HTTP 429 on a cold cache. `tier1.sh` retries once. If it persists, rerun later and report the delay.
- Robolectric cannot exercise CameraX, MediaCodec, `AudioRecord`, foreground services or permissions. Those are tier 2 or 3. Use the fake `FrameSource`/`AudioSource` in tier 1.
- OpenCV on the JVM uses `org.openpnp:opencv`. Call `nu.pattern.OpenCV.loadShared()` in test setup.
- Synthetic fixtures are generated deterministically (seeded) by `:testing:fixtures`. Never hand-edit generated files.
- Gradle builds are not safe to run twice at once in the same checkout. Run one at a time.

## Proof status of this skill (F02)

Proved in a cloud container: Launch, Doctor, Drive (`tier1.sh`), Evidence, Cleanup, and the `setup-screen` feature. **Not proved**: tier 2 (needs CI, run by the PR), tier 3, and every command marked as a stub or "not implemented yet" in `features/`.
