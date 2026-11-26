# BellLap: product and delivery plan

BellLap is an Android app that records the finishing order of cycling criteriums and other multi-lap races. It uses the phone's camera and microphone:

- It listens for the **bell lap** bell.
- It arms the camera.
- It starts recording when the **chequered flag** is seen.
- It marks each rider **crossing the finish line**.
- It gives the commissaire a frame-accurate **review** tool for setting placings.

| Document | Purpose |
|---|---|
| This file | Requirements, features, MVP, build sequence, CI/CD, AI SDLC, developer workflow |
| [research/technical-architecture.md](research/technical-architecture.md) | Android technical research: proof points, risks, spikes |
| [research/pstack-setup.md](research/pstack-setup.md) | pstack skills review and how they are installed for cloud agents |
| [VERIFICATION.md](VERIFICATION.md) | Verification plan for cloud agents, which applies from the first build |
| [../CLAUDE.md](../CLAUDE.md) | Working rules for every agent session |

Status legend: `[ ]` not started, `[x]` done. Feature IDs (`F00`…) match the GitHub issue titles.

---

## 1. User requirements

These were captured in the requirements interview on 2026-11-26.

### 1.1 Users and context

- [ ] **Primary user:** a commissaire or finish judge. They set the phone up on a tripod beside the finish line, mostly side-on, and leave it running hands-off for an event of 30–90 minutes.
- [ ] **Event shape:** several grades race on the same day and each finishes separately, minutes apart. Each grade has its own bell and flag. **Each finish becomes one acquisition file.**
- [ ] **Devices:** Android 10+ (minSdk 29) on mid-range phones. Features that need more (120/240 fps, locked-screen camera) are switched on per device after a capability probe.
- [ ] **Review happens on the capture phone**, possibly while the app is still ARMED for the next grade.

### 1.2 Operating modes (state machine)

```
SETUP ──(Standby)──► STANDBY ──(bell heard │ "Bell now")──► ARMING (10 s) ──► ARMED
  ▲                                                                   │  ▲
  │                                               (flag seen │ "Record now")│
  │                                                                   ▼  │
REVIEW ◄────────────────(user)─────────────── ACQUISITION ──(flag absent T s │ "Stop")
```

- [ ] **SETUP:** full-screen live camera preview for tripod alignment.
  - The user draws or refines the finish line with touch, or accepts a suggested line from automatic detection of a roughly vertical white line.
  - A settings overlay covers camera selection, resolution and fps, which automations are on, the flag timeout, and alerts.
  - A status overlay always shows the current mode.
- [ ] **STANDBY:** listens for the bell on the microphone.
  - **Default:** screen dimmed to black and kept on, which is reliable and costs little power on OLED.
  - **Advanced option:** "allow screen lock". It is offered only when the device passes the self-test (F25/F33).
  - A large **"Bell now"** button arms the app manually.
- [ ] **ARMING:** when the bell is heard, the app restores screen brightness. After **10 s** it plays a series of loud beeps at maximum volume and enters ARMED.
- [ ] **ARMED:** the camera runs and frames are analysed for a chequered flag. Nothing is recorded.
  - A **"Record now"** button starts ACQUISITION manually.
- [ ] **ACQUISITION:** records video to a new file and detects finish-line crossings, beeping once per finisher.
  - It stops when the flag has not been seen for a **configurable timeout** (default 15 s), or when the user taps **"Stop"**.
  - A chime plays and the app returns to ARMED.
- [ ] **REVIEW:**
  - List of acquisitions, auto-named "<event/date> Finish N" and renameable, e.g. to "A grade".
  - Video player with keyframes for each finisher.
  - Step **±1 frame**, and jump to the **previous or next finisher**.
  - **Pinch zoom and pan**.
  - Add, move or delete finisher marks.
  - A **free-text note** per finisher, e.g. "blue kit, white helmet, #23?", and an ordered finish list.
  - **Share the current frame** (zoomed crop or full) as an image through the Android share sheet.
  - Share the finish list as text.

### 1.3 Audio cues

- [ ] All cues play through the phone speaker at **maximum volume**, overriding the media volume during the cue and restoring it afterwards. The cues are armed beeps, one beep per finisher, and the stand-down chime.

### 1.4 Bell detection

- [ ] Ship a **generic model**: a cheap spectral gate, then YAMNet bell-family classes.
- [ ] The **"Bell now"** manual button is always available.
- [ ] A bug report can include a **recording of the bell** made in the app.
- [ ] **Investigate** retraining or personalising the model on the device from local bell recordings, using few-shot YAMNet embeddings and a small head. If that is feasible, ship local recording and model refinement (F34).

### 1.5 Bug reports

- [ ] A bug report is created in the app.
  - It attaches execution traces (structured event log, rolling log files, device capability dump).
  - The user can optionally record the bell.
  - The user can nominate captured video files, which are trimmed or downscaled before upload.
  - The report becomes a **GitHub issue** (label `field-report`).
- [ ] **Backend:** a Cloudflare Worker that acts as a GitHub App with `issues:write` only. The app never holds a GitHub token.
  - Media uploads go to **private R2** through presigned URLs.
  - Requests are verified with Play Integrity / App Check and rate-limited.

### 1.6 Deferred by decision

These are post-MVP: pre-roll ring buffer, 120/240 fps, locked-screen standby, Wear OS remote ("start acquisition now"), ML flag detector, rider tracking, and on-device bell personalisation.

The following were **not requested**: start-list import, CSV/PDF results export, time gaps.

### 1.7 Open questions

- [ ] **Repo visibility:** you said "private", but `surpdeh/belllap` is currently **public**.
  - Until it is made private, real race footage and field-report media must **not** be committed (no LFS). They live only in a private bucket.
  - The plan assumes the repo becomes private, or that footage stays in the bucket either way.
- [ ] **Test footage:** the plan uses a small labelled golden set (LFS once the repo is private, otherwise the bucket) plus an optional larger bucket. Bucket choice and credentials are still to be decided (F03b).
- [ ] **Play Store distribution:** decide before F41. Until then, releases are debug/internal APKs on GitHub Releases.

---

## 2. Technical architecture (summary)

Full proof points are in [research/technical-architecture.md](research/technical-architecture.md).

| Concern | Decision |
|---|---|
| Platform | Kotlin, Jetpack Compose, **minSdk 29**, targetSdk 36 (plan for 37), AGP 8.x, JDK 21 |
| Session lifetime | One `SessionForegroundService` with `foregroundServiceType="microphone\|camera"`, **started from the visible UI** when the user taps Standby. A camera or microphone FGS cannot be created from the background on Android 14+. Default standby is dimmed and kept on. |
| Audio | `AudioRecord` at 16 kHz mono, using the `UNPROCESSED` source if supported and `MIC` otherwise. Stage-0 FFT gate on 1.5–8 kHz tonal decaying peaks, then YAMNet (LiteRT) bell classes on candidates, then a persistence rule (N hits in 3 s). |
| Camera (MVP) | CameraX 1.7 `SessionConfig` with Preview, ImageAnalysis (~640×480 luma) and VideoCapture at 30/60 fps. Frame-rate range set explicitly. |
| Camera (later) | `HighSpeedVideoSessionConfig` at 120/240 fps, with a GL preview tap for analysis and a MediaCodec ring buffer for pre-roll |
| Flag | Classical texture detector (X-corner polarity voting, bimodal contrast, motion energy) with hysteresis. Later an EfficientDet-Lite0 LiteRT model (Apache-2.0, not AGPL YOLO). |
| Finish line | Auto suggestion from OpenCV white mask, Canny and HoughLinesP (±20° of vertical). Crossings by **slit-scan virtual photo finish** along the line, with background model and onset detection. Counts are suggestions that the commissaire confirms in review. |
| Persistence | One MP4 per acquisition plus sidecar `acq_<id>.json` (line geometry, fps, crossings by **PTS**, flag intervals, app version). Short GOP (≤1 s) for exact seeks once a custom encoder exists. |
| Review | Media3 ExoPlayer with `SeekParameters.EXACT` and scrubbing mode. ±1 frame by PTS. Finish "loupe" decodes ±30 frames around a keyframe for instant stepping. Compose `graphicsLayer` pinch zoom and pan. |
| Testability | `AudioSource`, `FrameSource`, `Clock` and `BeepSink` interfaces. Detectors are **pure Kotlin/JVM modules**. File-backed sources replay fixtures in JVM tests, on the emulator and in an in-app Replay mode. |
| Bug reports | Timber file tree, JSONL event log, `ApplicationExitInfo`, bundle builder, Worker + R2 |

### 2.1 Module layout

```
:app                 Compose UI, navigation, onboarding, Replay mode (debug)
:core:model          Acquisition, CrossingEvent, LineGeometry, Sidecar (kotlinx.serialization)
:core:statemachine   pure-Kotlin reducer State/Event/Effect, injected Clock
:core:service        SessionForegroundService, brightness/wake, BeepPlayer
:core:log            JSONL event log, Timber file tree
:audio:source        AudioSource + Mic / WavFile impls
:audio:bell          spectral gate, YAMNet runner, (later) personalisation
:vision:core         Frame, FrameSource, luma utils, OpenCV wrappers
:vision:flag         texture flag detector (later LiteRT detector)
:vision:finishline   white-line auto-detect, slit-scan strip, crossing detector
:capture             CameraX session manager, recorder, (later) GL tap + ring buffer
:review              player, loupe, finisher list/notes, share
:bugreport           bundle builder, uploader client
:testing:fixtures    fixture manifest, loaders, synthetic generators
:testing:harness     JVM replay runner + metrics; androidTest replay
backend/worker       Cloudflare Worker (GitHub App issue proxy + R2 presign)
```

### 2.2 Top technical risks (validate early)

1. The process or camera does not survive a locked screen on OEM phones. Mitigation: dimmed-standby default, and the F25 self-test.
2. Bell false positives or negatives in real race acoustics. Mitigation: cascade detector, manual button, metrics on real footage (F14 spike).
3. Bunched riders merge in the slit-scan. Mitigation: review is designed around human confirmation.
4. Chequered flag robustness: small or furled flags, chequered banners. No public dataset exists.
5. Battery, thermal and storage use over long events.
6. Platform drift: Android 17 audio hardening, Play wake-lock vitals, 16 KB page alignment.

---

## 3. Feature plan

Each feature becomes one GitHub issue, `[Fxx] Title`, with acceptance criteria and **required verification evidence**. Milestones:

- **M0 Foundations:** a walking skeleton that agents can verify.
- **M1 MVP.**
- **M2 Post-MVP.**

### M0: Foundations (walking skeleton and verification first)

- [x] **F00 Project skeleton:** ([#1](https://github.com/surpdeh/belllap/issues/1))
  - Gradle multi-module layout (§2.1) with a version catalog.
  - Compose app that launches to a placeholder SETUP screen.
  - ktlint/detekt and Android lint.
  - `./gradlew build` passes in the cloud container.
- [x] **F01 CI pipeline:** ([#2](https://github.com/surpdeh/belllap/issues/2)) build, unit tests, lint, debug APK artifact, emulator smoke test with screenshot and screen-recording evidence, and a PR evidence comment (§4).
- [x] **F02 Verification skill:** ([#3](https://github.com/surpdeh/belllap/issues/3)) `verify-belllap` covering launch, doctor, drive, evidence and cleanup.
  - Proven end to end, both in the cloud container (JVM tests and Robolectric/Roborazzi screenshots) and in CI (emulator).
  - Includes a feature map ([VERIFICATION.md](VERIFICATION.md)).
- [x] **F03 Fixture framework:** ([#4](https://github.com/surpdeh/belllap/issues/4))
  - `FrameSource` and `AudioSource` interfaces with file-backed implementations.
  - **Synthetic fixture generators**: a bell-tone WAV with crowd or pink noise, a chequered-flag video, and riders crossing a line. These work with no real footage.
  - A fixture manifest with labels.
- [ ] **F03b Real footage pipeline:** ([#5](https://github.com/surpdeh/belllap/issues/5)) a private bucket (or LFS once the repo is private), a downloader script with a CI secret, a labelling format (`labels.json`: bell times, flag intervals, crossing frames), and contribution docs.
- [ ] **F04 Session state machine:** ([#6](https://github.com/surpdeh/belllap/issues/6))
  - Pure-Kotlin reducer for SETUP, STANDBY, ARMING, ARMED, ACQUISITION and REVIEW, with manual overrides and timeouts.
  - Exhaustive unit tests using virtual time.
  - Optional TLA+ check of the core properties (pstack `/create-formal-verification-skill`).
- [x] **F05 Event log and tracing:** ([#7](https://github.com/surpdeh/belllap/issues/7))
  - A JSONL structured event log of state transitions, detector scores and device info.
  - A Timber rolling file tree.
  - `ApplicationExitInfo` capture.
- [ ] **F24 Replay mode (debug builds):** ([#8](https://github.com/surpdeh/belllap/issues/8))
  - Runs the whole pipeline (state machine and detectors) on a chosen MP4 and WAV, instead of the camera and mic.
  - This is the main way to verify on the emulator and in the field.

### M1: MVP

Setup and capture:

- [ ] **F10 Camera setup screen:** ([#9](https://github.com/surpdeh/belllap/issues/9)) live preview; settings overlay with camera, resolution, 30/60 fps, automations, flag timeout and alerts; settings persisted with DataStore; mode status overlay.
- [ ] **F11 Finish-line drawing:** ([#10](https://github.com/surpdeh/belllap/issues/10)) draw a line with touch, then drag its endpoints to refine it. Stored in normalised coordinates and persisted.
- [ ] **F12 Automatic white-line detection:** ([#11](https://github.com/surpdeh/belllap/issues/11)) a suggested line with a confidence score. The user can accept or edit it. JVM tests run on fixture stills.
- [ ] **F13 Session foreground service and standby:** ([#12](https://github.com/surpdeh/belllap/issues/12))
  - The microphone and camera FGS starts from the UI.
  - Dimmed-standby screen; brightness restored when the bell is heard.
  - Notification actions: Bell now, Record now, Stop, Exit.
  - Onboarding for permissions, the battery-optimisation exemption and OEM tips.
- [ ] **F14 Bell detection v1:** ([#13](https://github.com/surpdeh/belllap/issues/13))
  - Spectral gate, then YAMNet (LiteRT) bell classes, then a persistence rule.
  - Sensitivity setting and a "Bell now" button.
  - Metrics on fixtures: target recall ≥ 0.9 and < 0.2 false alarms per hour.
- [ ] **F15 Alert player:** ([#14](https://github.com/surpdeh/belllap/issues/14)) armed beep series at +10 s, beep per finisher and stand-down chime, all at maximum volume through the alarm stream. Volume is restored afterwards.
- [ ] **F25 Device capability probe:** ([#15](https://github.com/surpdeh/belllap/issues/15)) dumps camera hardware level, fps ranges, supported session configurations and audio sources. Included in bug reports.

Detection and recording:

- [ ] **F16 Flag detection v1:** ([#16](https://github.com/surpdeh/belllap/issues/16))
  - Texture detector running on ARMED analysis frames.
  - Hysteresis, and a configurable absence timeout (default 15 s).
  - Metrics on fixtures.
  - Debug overlay showing the detection box.
- [ ] **F17 Acquisition recording:** ([#17](https://github.com/surpdeh/belllap/issues/17))
  - CameraX Recorder at 30/60 fps, one MP4 per acquisition, plus a sidecar JSON.
  - Auto-naming, and "Record now" / "Stop".
  - Free-storage guard.
- [ ] **F18 Finish-crossing detection v1:** ([#18](https://github.com/surpdeh/belllap/issues/18))
  - Slit-scan along the line, with onset detection.
  - Live beep for each crossing.
  - Crossings saved to the sidecar.
  - A post-process refinement pass over the saved file.
  - Metrics on fixtures: count error and frame error.

Review and output:

- [ ] **F19 Review player:** ([#19](https://github.com/surpdeh/belllap/issues/19))
  - Acquisition list with rename and delete.
  - Exact seek, ±1 frame steps, previous/next finisher, pinch zoom and pan.
  - Add, move or delete finisher marks.
- [ ] **F20 Placings and notes:** ([#20](https://github.com/surpdeh/belllap/issues/20)) a free-text note per finisher, an ordered finish list per acquisition, and sharing the list as text.
- [ ] **F21 Share frame:** ([#21](https://github.com/surpdeh/belllap/issues/21)) share the current frame, full or zoomed crop, as a PNG/JPEG through the share sheet using FileProvider.

Bug reports:

- [ ] **F22 In-app bug report:** ([#22](https://github.com/surpdeh/belllap/issues/22))
  - Description field.
  - Trace bundle and capability dump attached automatically.
  - Optional bell recording.
  - Optional video selection, trimmed to a window around a chosen point.
  - Consent screen before upload.
  - Upload progress and retry.
- [ ] **F23 Bug report backend:** ([#23](https://github.com/surpdeh/belllap/issues/23))
  - Cloudflare Worker acting as a GitHub App, creating issues labelled `field-report`.
  - R2 presigned uploads.
  - Integrity check, rate limit and size caps.
  - Worker unit tests and a CI deploy.

**MVP exit criteria:**

- [ ] A fixture race (synthetic, then real) replayed through Replay mode on the emulator produces BELL, ARMED, FLAG, the expected crossings ±1, and STOP, with a playable file and sidecar.
- [ ] A field test on two physical phones (one Pixel, one Samsung) through a full club race, with a bug report submitted from the field.

### M2: Post-MVP (rough priority order)

- [ ] **F30 Pre-roll ring buffer:** ([#24](https://github.com/surpdeh/belllap/issues/24)) custom MediaCodec encoder, keeping the last N seconds encoded in RAM and flushing them on flag detection. Short GOP.
- [ ] **F31 High-fps capture:** ([#25](https://github.com/surpdeh/belllap/issues/25)) 120/240 fps through `HighSpeedVideoSessionConfig`, with a GL preview tap for flag and slit-scan analysis. Only on capable devices.
- [ ] **F32 Photo-finish strip view in review:** ([#26](https://github.com/surpdeh/belllap/issues/26)) tapping a column seeks to that frame.
- [ ] **F33 Locked-screen standby:** ([#27](https://github.com/surpdeh/belllap/issues/27)) a per-device self-test, wake on bell with `setShowWhenLocked`/`setTurnScreenOn`, and a headless camera fallback.
- [ ] **F34 Bell personalisation:** ([#28](https://github.com/surpdeh/belllap/issues/28))
  - Feasibility study of a few-shot YAMNet-embedding prototype against an on-device trained head.
  - If feasible, local recording, refinement and A/B metrics.
- [ ] **F35 ML flag detector:** ([#29](https://github.com/surpdeh/belllap/issues/29)) EfficientDet-Lite0 single-class model, with a training pipeline and dataset from consented field footage.
- [ ] **F36 Rider detection and tracking:** ([#30](https://github.com/surpdeh/belllap/issues/30)) splits bunches and refines front-wheel crossing time in post-process.
- [ ] **F37 Wear OS remote:** ([#31](https://github.com/surpdeh/belllap/issues/31)) watch tile and app with "Record now", "Bell now", "Stop" and a status view.
- [ ] **F38 Volume-key shortcut:** ([#32](https://github.com/surpdeh/belllap/issues/32)) a hardware key arms or starts recording while the app is in the foreground.
- [ ] **F40 Real-device CI:** ([#33](https://github.com/surpdeh/belllap/issues/33)) nightly Firebase Test Lab matrix of capability probes and camera smoke tests.
- [ ] **F41 Release pipeline:** ([#34](https://github.com/surpdeh/belllap/issues/34)) signed AAB/APK on tag, GitHub Release, Play internal track (pending decision §1.7).

---

## 4. CI/CD (GitHub Actions)

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | PR, push to `main` | **build-test:** JDK 21 and Gradle cache; `./gradlew lintDebug detekt testDebugUnitTest verifyRoborazziDebug assembleDebug`. Uploads the APK, JUnit and lint reports and Roborazzi screenshots, and writes a job summary. **emulator-e2e** (needs build-test): KVM plus `reactivecircus/android-emulator-runner` (API 34, ATD x86_64); `connectedDebugAndroidTest`, including the Replay e2e on fixtures; `adb screenrecord`; screenshots; logcat. Uploads all of these. **evidence-comment:** pushes PNG/GIF evidence to the `ci-evidence` branch under `pr-<n>/<sha>/` and upserts one PR comment that embeds them, with test totals and detector metrics. |
| `detector-metrics.yml` | PR touching `audio/`, `vision/`, `testing/`; nightly | Runs the JVM replay harness on the synthetic and golden fixtures (and the full bucket set nightly, using a secret). Fails if a metric falls below its threshold in `testing/thresholds.json`. Posts a metrics table. |
| `claude.yml` | `@claude` in an issue or PR comment, or issue labelled `agent:go` | `anthropics/claude-code-action` runs an agent that implements the issue, pushes a branch and opens a PR. Needs the `CLAUDE_CODE_OAUTH_TOKEN` (or `ANTHROPIC_API_KEY`) secret. |
| `worker.yml` (F23) | PR touching `backend/` | `npm test` (vitest + miniflare). Runs `wrangler deploy` on main using the `CLOUDFLARE_API_TOKEN` secret. |
| `release.yml` (F41) | Tag `v*` | Signed release APK/AAB using a keystore from secrets, then a GitHub Release. |
| `nightly-devices.yml` (F40) | Cron | Firebase Test Lab on physical devices. |

- [ ] Branch protection on `main` (Settings → Branches → rule for `main`; a repo admin applies this once):
  - Require a pull request before merging, with **1 approving review** (the developer).
  - Require status checks to pass, **strict** (branch up to date): `build-test` and `emulator-e2e`. Do not require `evidence-comment`; it only reports.
  - Allow **squash merging** only (Settings → General → Pull Requests), and disable merge commits and rebase merging.
  - Restrict force pushes and deletions on `main`.
  - Leave the `ci-evidence` branch unprotected: the `evidence-comment` job pushes to it with `GITHUB_TOKEN`.
- [ ] Dependabot for Gradle and GitHub Actions.
- [ ] The workflows already in the repo skip the Gradle jobs cleanly until F00 adds `gradlew`.

---

## 5. AI SDLC

- [x] **pstack** (MIT, Lauren Tan) is vendored into `.claude/skills` (59 skills) and `.claude/agents` (2 agents). Every cloud session that clones the repo gets it. Details are in [research/pstack-setup.md](research/pstack-setup.md).
- [x] The **cloud environment "belllap"** allows `dl.google.com` and installs the Android SDK (platform 36, build-tools 36) in its setup script, with `ANDROID_HOME=/opt/android-sdk`.
  - Not yet proven: a fresh session on that environment.
  - No KVM in cloud containers, so **no emulator there**. Emulator evidence comes from CI.
- [ ] Run `/setup-pstack` once and commit `.claude/rules/pstack-models.md`.
- [ ] F02: build `verify-belllap` with `/create-verification-skill` once F00 builds, and prove it end to end. A draft is committed now.
- [ ] F04: `/create-formal-verification-skill` for the session state machine.
- [ ] Keep `/maintain-verification-skill` current. Agents run it whenever they change a flow.

The **verification plan from the first build** is in [VERIFICATION.md](VERIFICATION.md). It works in three tiers:

1. **Cloud container:** JVM unit tests, detector metrics on synthetic fixtures, Robolectric/Roborazzi Compose screenshots, and `assembleDebug`.
2. **CI emulator:** an instrumented replay e2e, screen recording and screenshots.
3. **Physical devices:** a manual or Firebase Test Lab checklist for camera, mic, FGS and lock.

Every PR states which tier proved what. A check that could not run is reported as *inconclusive*, never as a pass.

---

## 6. Developer workflow

1. **Backlog:** each feature is a GitHub issue `[Fxx] …` in milestone M0, M1 or M2, with labels `feature` and `area:*`. Each issue holds:
   - goal;
   - acceptance criteria;
   - **verification evidence required**;
   - dependencies.
2. **Pick an issue:** take the lowest open Fxx whose dependencies are met.
3. **Launch an agent:** two options.
   - **Claude Code on the web or in the app (interactive):** start a session on `surpdeh/belllap` in the **belllap** environment with the prompt `Implement issue #<n>. Follow CLAUDE.md.` Steer it live in the session.
   - **GitHub (async):** comment `@claude implement this issue` on the issue. Steer it with further `@claude` comments on the issue or PR.
4. **The agent delivers a PR** that `Closes #<n>`, using the PR template.
   - The **Verification** section lists exact commands with their output.
   - Screenshots come from Roborazzi in the cloud container, and from the emulator in CI.
   - Screen recordings come from CI.
   - Test and metric reports come from CI.
   - CI adds the evidence comment.
5. **Review:** you review the evidence. Request changes with `@claude` on the PR or in the session, then merge.
6. **Field reports** arrive as `field-report` issues. Triage them into bugs and attach the media to fixtures, with consent.
