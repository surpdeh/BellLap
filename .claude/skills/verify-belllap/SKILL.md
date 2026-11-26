---
name: verify-belllap
description: "Prove a BellLap (Android app) change works and collect evidence for the PR. Covers the cloud container (JVM tests, detector replay metrics, Roborazzi Compose screenshots, assembleDebug) and the CI emulator tier (replay e2e, screen recording). Use before opening or updating any BellLap PR, or when asked to verify, screenshot or demo a feature."
---

# Verify BellLap

> **DRAFT.** Written before the Gradle project exists. F02 must complete this skill with `/create-verification-skill` and prove each section end to end. Until then, the commands below are the intended contract. A command that does not exist yet is **inconclusive**, not a pass. The full plan is in `docs/VERIFICATION.md`.

## Surfaces

- **Tier 1, cloud container (you are here):** no KVM, so **no emulator**. JVM tests, Robolectric and Roborazzi screenshots, and replay metrics on fixtures.
- **Tier 2, GitHub Actions emulator:** runs automatically on the PR. You read its results; you cannot run it.
- **Tier 3, physical phone:** the developer runs it. List what still needs a phone.

## Launch (tier 1)

```bash
test -n "$ANDROID_HOME" || export ANDROID_HOME=/opt/android-sdk
./gradlew --version          # ready = prints Gradle and JVM 21
```

**If the SDK is missing:** the belllap environment setup script installs it. Run the same commands by hand (see `docs/PLAN.md §5`). If `dl.google.com` is refused, report that the environment is wrong. Do not work around it.

## Doctor

```bash
ls "$ANDROID_HOME/platforms" | grep android-36 && ./gradlew help -q && echo DOCTOR_OK
```

## Drive

| Purpose | Command | Evidence location |
|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest` | `**/build/reports/tests/testDebugUnitTest/index.html`, summary line |
| Detector metrics | `./gradlew :testing:harness:replay` | `testing/harness/build/reports/metrics.json` (compare with `testing/thresholds.json`) |
| Screenshots | `./gradlew recordRoborazziDebug` | `**/build/outputs/roborazzi/*.png`. **Open them with the Read tool and check what they show.** |
| Build | `./gradlew lintDebug assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| Single module | `./gradlew :vision:flag:test --tests '*Flag*'` | |

**UI tests:** use stable handles, `Modifier.testTag("…")` with tags declared in `app/src/main/.../TestTags.kt`. Never use coordinates.

## Evidence

- Paste the command summary lines verbatim into the PR **Verification** section.
- Commit **no** evidence binaries to feature branches. CI publishes tier 2 evidence to the `ci-evidence` branch. For tier 1 screenshots, either reference the Roborazzi golden diffs, or upload the PNGs as a PR comment through CI.
- Use the real user path: drive the ViewModel through UI events, not test-only setters.

## Cleanup

- Stop only Gradle daemons you started (`./gradlew --stop`). Keep the `build/` reports until the PR is opened.

## Gotchas

- Robolectric cannot exercise CameraX, MediaCodec or AudioRecord. Use the fake `FrameSource`/`AudioSource`.
- OpenCV on the JVM uses `org.openpnp:opencv`. Call `nu.pattern.OpenCV.loadShared()` in the test setup.
- Synthetic fixtures are generated deterministically (seeded). Never hand-edit the generated files.
