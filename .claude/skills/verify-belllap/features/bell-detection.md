# Bell detection

Status: **implemented (F14)**. In STANDBY the app listens on the microphone (16 kHz mono, `UNPROCESSED` if the device supports it, else `MIC`). When it hears a bell lap it dispatches `BellDetected`, and the session goes to ARMING, then ARMED after 10 s. A big **Bell now** button does the same by hand. The detector is a cascade in `:audio:bell` (pure Kotlin): a spectral gate (stage 0), YAMNet bell-family scores (stage 1, LiteRT on the device), then persistence (N hits in 3 s, or a train of strikes). The settings overlay has a Low / Medium / High sensitivity.

## Sub-features

- `BD-1` stage 0 passes a struck, decaying, tonal bell in 1.5–8 kHz and rejects whistles, squeal, claps, horns, voice and silence.
- `BD-2` stage 1 scores the candidate window with YAMNet's bell family (`YamnetClassMap.BELL_FAMILY`).
- `BD-3` persistence: one `BellDetected` per bell lap, then a refractory period.
- `BD-4` metrics on synthetic fixtures meet `testing/thresholds.json`: recall ≥ 0.9, false alarms < 0.2 per hour (6 h of ambience), latency ≤ 1.5 s.
- `BD-5` the sensitivity setting persists and changes the detector.
- `BD-6` STANDBY shows the **Bell now** button; tapping it arms.
- `BD-7` capture uses `UNPROCESSED` when supported, else `MIC`.
- `BD-8` a real bell is detected on a phone (tier 3).

## How to get to it (user POV)

- SETUP, then **Standby** (bottom centre). The black STANDBY screen shows the amber **Bell now** button and "Listening for the bell (Medium sensitivity)".
- Ring a bell (tier 3), or tap **Bell now**: the mode changes to ARMING, then ARMED.
- Sensitivity: SETUP, **Settings**, "Bell sensitivity" chips under "Bell detection".

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **BD-1, BD-3, BD-5 (tier 1):** `./gradlew :audio:bell:test` (`BellDetectorTest`), `:app:testDebugUnitTest --tests '*SessionControllerTest*' --tests '*SettingsRepositoryTest*'`.
- **BD-4, stage 1 off (tier 1, CI-gating):** `./gradlew :testing:harness:replay`. Writes `testing/harness/build/reports/metrics.json` and `bell-trace.csv` (every candidate, score and detection).
- **BD-2 and BD-4 with the real YAMNet model (tier 1, needs network for pip and the model):** `scripts/bell/replay-yamnet.sh`. It dumps every candidate window, scores them with Python LiteRT, and replays with those scores. Writes `metrics-yamnet.json` and `bell-trace-yamnet.csv`. CI runs it too.
- **BD-6:** `./gradlew :app:testDebugUnitTest --tests '*StandbyScreenTest*' --tests '*SetupScreenTest*'`. Goldens: `app/src/test/screenshots/standby_screen.png` and `standby_arming.png`. Open them with the Read tool.
- **BD-7:** `./gradlew :audio:android:testDebugUnitTest` checks the rule. Which source a phone actually uses is logged by Timber ("Bell capture started: …"), so it needs a device (tier 3).
- **BD-2 on Android (tier 2):** `YamnetOnDeviceTest` in `:audio:android` runs the LiteRT model on the CI emulator over the synthetic fixture. Read the `BellLapYamnet` logcat lines in the evidence.
- **BD-8:** tier 3 only. Report it as inconclusive until a developer has rung a bell at a phone.

## Gotchas

- LiteRT has no desktop JVM runtime. That is why stage 1 runs out of process in the harness. Do not add a fake that pretends to be YAMNet.
- The model (`yamnet.tflite`, Apache-2.0) is fetched by `:audio:android:fetchYamnet`, with its SHA-256 checked against `gradle.properties`. It is never committed.
- Synthetic bells are sine partials. YAMNet hears a rapidly rung one as "Alarm clock" or "Telephone bell ringing", which is why those classes are in the bell family. Real bells may score differently: that is tier 3.
- `metrics.json` with stage 1 off is an upper bound on false alarms, because stage 1 can only remove candidates. The strike-train rule bypasses stage 1, so its false alarms are measured with stage 1 off too.
