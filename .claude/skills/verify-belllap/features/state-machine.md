# Session state machine

Status: **not implemented yet** (F04, `:core:statemachine` is an empty module). This describes what to prove once it exists, so the first F04 PR can fill in real commands.

The pure-Kotlin reducer moves a session through SETUP, STANDBY, ARMING, ARMED, ACQUISITION and REVIEW, from events (bell, flag, manual overrides, timeouts) in virtual time.

## Sub-features

- `SM-1` bell detection moves STANDBY to ARMING, then ARMED.
- `SM-2` flag detection moves ARMED to ACQUISITION.
- `SM-3` the flag timeout and manual stop move ACQUISITION to REVIEW.
- `SM-4` manual overrides (`Bell now`, stop) work from any valid state.
- `SM-5` invalid events never leave the state machine in an undefined state.

## How to get to it (user POV)

- Start a session from SETUP. Transitions then come from the detectors, timeouts and manual buttons.
- UI wiring arrives with later features. Today there is no user path.

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **Tier 1 (all SM-*):** `./gradlew :core:statemachine:test`. Expect one test per transition and per invalid event, all passing, using virtual time (no `Thread.sleep`).
- **Today:** the module has no tests. Report SM-1..SM-5 as **inconclusive: not implemented**.
- **Tier 2:** the replay e2e asserts the event log reads `BELL → ARMING → ARMED → FLAG → CROSSING×N → STOP` (see [replay](./replay.md)).

## Gotchas

- The module must stay pure Kotlin/JVM with no `android.*` imports.
- For the optional formal check, see `/create-formal-verification-skill`.
