# BellLap verification map

The maintained source for verifying user-facing behaviour. Read this index, then use the matching feature file as the recipe. Update the file in the same PR that changes the behaviour.

## Baseline preconditions

- Work from the repo root in the cloud container. `ANDROID_HOME=/opt/android-sdk`, JDK 25 (bytecode target 21), `./gradlew` runs (Launch and Doctor in `../SKILL.md`).
- Run Doctor first. It must print `DOCTOR_OK`.
- Tier 1 has no emulator, no camera and no microphone. Use fakes and fixtures.
- Run one Gradle build at a time.

## Driving conventions

- The harness is Gradle + JUnit + Robolectric/Compose test rule (tier 1), and `connectedDebugAndroidTest` in CI (tier 2).
- Find UI by `testTag` declared in `app/src/main/kotlin/net/belllap/ui/TestTags.kt`. Never by coordinates.
- The time base is the frame PTS, never the wall clock. Tests use virtual time.
- Treat commands as literal.

## Proof and skip reporting

- Evidence goes to `evidence/tier1/<run-id>/` via `scripts/tier1.sh`. Record the feature ID and entry point with every artifact.
- Screenshots: open them with the Read tool and describe what you see.
- A feature marked **Status: not implemented** has no command that proves it yet. Report it as inconclusive. Do not report it as verified through another path.
- Report an unreachable path with the attempted command and the unmet precondition.

## Feature entry contract

H1 title, one paragraph of user-visible behaviour, then exactly four H2s in order: `Sub-features`, `How to get to it (user POV)`, `Driving it with Gradle and Compose tests`, `Gotchas`.

## Features

- [Setup screen](./setup-screen.md): the launch screen with the mode status overlay. **Implemented (F00).**
- [Session state machine](./state-machine.md): SETUP to STANDBY, ARMING, ARMED, ACQUISITION and REVIEW. **Not implemented yet (F04).**
- [Replay mode](./replay.md): run the pipeline on a fixture MP4 and WAV. Fixtures and metrics (RP-1, RP-2) done in F03; in-app replay (RP-3, RP-4) **not implemented yet (F24).**
- [Review screen](./review.md): finishing order and crossing marks for commissaire review. **Not implemented yet (M1).**
