# BellLap: Technical Architecture Research

Researched 2026-11-26 by an Opus research sub-agent. Edited for the repo; the decisions taken from it are summarised in [../PLAN.md §2](../PLAN.md#2-technical-architecture-summary).

> **Platform baseline.** Target SDK 36 now, and plan to move to 37 because Android 17 adds background-audio hardening.
> The researcher recommended minSdk 30, which gives one foreground-service-type and while-in-use model. The **product decision is minSdk 29** (Android 10).
>
> - API 29 has no `camera`/`microphone` FGS types and no while-in-use restriction, so the manifest types are applied conditionally. `ServiceCompat.startForeground` takes the type mask on 29+, and the camera/mic bits only exist on 30+.
> - CameraX needs API 24+.
>
> Versions: CameraX 1.7.0 (stable 18 Nov 2026), Media3 1.9+, LiteRT 2.x, OpenCV 4.12+ from Maven Central.
>
> Not every source could be fetched. dontkillmyapp.com and android-developers.googleblog.com were unreachable, and their content here comes from search results or mirrors.

---

## A. Background audio, foreground services, lock screen and Doze

**Recommendation**

1. Use one long-lived `LifecycleService` with `foregroundServiceType="microphone|camera"`.
   - Call `startForeground(..., MICROPHONE|CAMERA)` while the activity is visible, when the user taps Standby.
   - The service then holds while-in-use (WIU) capability for its whole life. It can open the camera later even when locked, because it is no longer *creating* a camera FGS from the background.
2. Never try to start or upgrade the camera FGS at bell-detection time. The app is in the background by then.
3. **Default standby is dimmed, not locked:** keep the activity on screen with `FLAG_KEEP_SCREEN_ON`, a black UI and `screenBrightness ≈ 0.01`. This avoids background-activity-launch restrictions, keyguard and screen-wake problems. On detection, restore `BRIGHTNESS_OVERRIDE_NONE`.
4. **Locked mode is a fallback:** the FGS keeps listening and opens the camera headless, using an offscreen `SurfaceTexture`.
   - Screen wake is best effort, through `setShowWhenLocked(true)` and `setTurnScreenOn(true)`.
   - Do not rely on a full-screen intent. On Android 14+ it is granted by default only to calling and alarm apps.
5. Play beeps from the same WIU FGS, using `USAGE_ALARM` or `USAGE_ASSISTANCE_SONIFICATION` with `SoundPool`/`AudioTrack`. Android 17 requires a WIU FGS for background playback.
6. Hold no manual wake lock in STANDBY. The active `AudioRecord` keeps the CPU running, and audio wake locks are exempt from Play's metric. In ARMED and ACQUISITION, hold a partial wake lock for the session.
7. Onboarding asks for "Unrestricted" battery mode (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and shows OEM-specific tips from dontkillmyapp.

**Proof points**

- From Android 12 an app cannot start an FGS from the background without an exemption, and it cannot *create* a WIU-permission FGS from the background.
  - On Android 14+, creating a camera/mic FGS without the permission throws `SecurityException`. It must be started "while your app has a visible activity".
  - Source: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- FGS types and their required permissions; Play Console declarations are required for targetSdk 34+. Source: https://developer.android.com/develop/background-work/services/fgs/service-types
- Background camera access only through an FGS (Android 9+). Source: https://developer.android.com/guide/topics/media/camera
- Android 17 background-audio hardening: failures are silent; test with `adb shell cmd audio set-enable-hardening throw`. Source: https://developer.android.com/about/versions/17/changes/bg-audio
- Full-screen intent limits. Source: https://source.android.com/docs/core/permissions/fsi-limits
- Play wake-lock vitals (enforced since 1 Mar 2026; audio wake locks are exempt). Source: https://developer.android.com/topic/performance/vitals/wakelock
- OEM killers (Samsung, Xiaomi, OnePlus/Oppo, Huawei). Source: https://dontkillmyapp.com

**Risks**

- OEM skins may freeze the process during long locks.
- Some devices refuse to open the camera with the screen off. Handle `onDisconnected`/`onError` and retry.

**Spike:** a mic+camera FGS prototype. Lock the phone for 90 minutes and force Doze with `adb shell cmd deviceidle force-idle`, then simulate a detection that opens the camera and records 10 s. Run it on a Pixel, a Samsung and a Xiaomi. Log `ApplicationExitInfo` and battery % per hour.

## B. Bell sound detection

**Recommendation:** a three-stage cascade on 16 kHz mono audio.

1. **Stage 0, always on (~0.1 ms per 10 ms frame):**
   - 512-point STFT with 50% hop.
   - Spectral peaks tracked in the 1.5–8 kHz band.
   - A candidate needs: an onset (spectral flux), a tonal peak at least 15 dB above the local median, stable inharmonic partials, and decay longer than 150 ms.
   - High-pass at about 300 Hz to remove wind. The PA announcer and crowd sit mostly below 3 kHz.
2. **Stage 1, on candidates only:** YAMNet via MediaPipe Audio Classifier, or LiteRT directly so embeddings are exposed. Score the bell family:
   - Bell, Church bell, Jingle bell, Bicycle bell, Cowbell, Chime/Wind chime, Tuning fork.
   - Use the index→name CSV shipped with the model.
3. **Stage 2, personalisation (post-MVP F34):** a mean YAMNet 1024-d embedding of 3–5 user bell recordings, plus ambient negatives.
   - Cosine-similarity margin over at least 2 consecutive hops.
   - Later, a small dense head trained on embeddings, possibly on device.
4. Debounce and require persistence: a real bell lap is rung repeatedly, so require N hits within 3 s.
5. Use `AudioSource.UNPROCESSED` when `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is set, otherwise `MIC`.
   - Avoid `VOICE_RECOGNITION`, which is tuned flat only to 4 kHz.
   - Avoid `VOICE_COMMUNICATION`, whose noise suppression and AGC remove tonal content.

**Proof points**

- YAMNet: 16 kHz input, 0.96 s windows with 0.48 s hop, 521 classes plus a 1024-d embedding.
  - https://www.tensorflow.org/hub/tutorials/yamnet
  - https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/README.md
- Transfer learning on YAMNet embeddings. Source: https://www.tensorflow.org/tutorials/audio/transfer_learning_audio
- MediaPipe Audio Classifier with YAMNet runs in **12.29 ms on CPU on a Pixel 6**. That is about 2.5% of one core at 0.48 s hops, and under 0.5% behind the stage-0 gate. Source: https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier
- AudioSet bell ontology. Source: https://research.google.com/audioset/dataset/bicycle_bell.html
- Audio pre-processing and the UNPROCESSED source. Source: https://source.android.com/docs/core/audio/implement-pre-processing

**Risks**

- A brass handbell may not match AudioSet bells.
- The PA may play chimes.
- AGC and clipping at close range.
- The MediaPipe audio task does not expose embeddings. Use the LiteRT YAMNet model directly for stage 2.

**Spike:** run the cascade offline on the JVM over 20+ real bell laps and an hour of ambience, and plot a precision/recall curve. Targets: < 0.2 false triggers per hour and detection latency ≤ 1.5 s.

## C. Camera pipeline

**Recommendation**

- **ARMED:** CameraX 1.7 `SessionConfig` with Preview, ImageAnalysis (YUV, `KEEP_ONLY_LATEST`, ~640×480) and VideoCapture bound but idle.
  - Set the fps range explicitly, from `CameraInfo.getSupportedFrameRateRanges(sessionConfig)`.
- **ACQUISITION at 30/60 fps:** use the same session and call `Recorder.prepareRecording()`. Analysis continues, and recording starts within about one frame.
- **High-speed (post-MVP F31):** `HighSpeedVideoSessionConfig(videoCapture, preview, range, isSlowMotionEnabled=false)`.
  - High-speed sessions accept **no ImageAnalysis**. Camera2 high-speed sessions allow at most two surfaces, both preview or encoder.
  - To analyse frames, make Preview a GL `SurfaceTexture`. A shader samples the finish-line column(s) and a downscaled flag ROI, and `glReadPixels`/PBO reads them back.
- Switching sessions takes about 300–800 ms. Cover it with **pre-roll (F30)** or stay high-speed throughout ARMED.
  - The pre-roll is a custom `MediaCodec` encoder (Grafika `CircularEncoder` pattern) that keeps the last N seconds of encoded access units, IDR-aligned.
  - On flag detection, flush them to the muxer. A 1 s GOP keeps trimming simple.
  - 5 s at 1080p120 HEVC is about 30 MB of RAM.
- Use Camera2Interop only for manual exposure (short shutter to reduce motion blur) or to lock `CONTROL_AE_TARGET_FPS_RANGE`.

**Proof points**

- CameraX high-speed API and its device support: "rear cameras of almost all Pixel devices and select models from other manufacturers". Source: https://developer.android.com/blog/posts/introducing-camera-x-powerful-video-recording-and-pro-level-image-capture
- CameraX 1.6 and 1.7 release notes: `SessionConfig` stable; Media3 muxer the default and crash-resilient; `ImageProxy.getHardwareBuffer()`; Camera2Interop V2; `CameraXViewfinder` pinch zoom. Source: https://developer.android.com/jetpack/androidx/releases/camera
- Constrained high-speed allows at most two surfaces and overrides AE/AWB/AF.
  - https://developer.android.com/reference/android/hardware/camera2/CameraDevice
  - https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession
- No `ImageReader` in high-speed sessions. Source: https://groups.google.com/a/android.com/g/camerax-developers/c/8I215wm9f8Y
- Use-case combinations and stream sharing on FULL-level devices and below.
  - https://developer.android.com/media/camera/camerax/architecture
  - https://developer.android.com/media/camera/camera2/multiple-camera-streams-simultaneously
- Circular encoder.
  - https://source.android.com/docs/core/graphics/arch-st
  - https://github.com/google/grafika

**Risks**

- Many mid-range phones have no high-speed support, or support it only at 720p.
- Fps ranges may collapse to 30 when analysis is bound.
- Rolling shutter.
- Thermal throttling.
- The GL+MediaCodec work is significant.

**Spike:** a capability-probe screen (F25), plus a GL tap and ring buffer prototype on a Pixel.

## D. Chequered flag detection

**Recommendation**

- **MVP:** a classical texture detector on downscaled grayscale (~320×240), with an optional ROI near the line.
  - It looks for local contrast, a bimodal histogram, low saturation, 2-D periodicity or density of polarity-alternating X-corners, and temporal motion energy, because the flag is waved.
  - It fires when the detected area is at least 0.3% of the frame and persists for at least 0.5 s.
  - `findChessboardCorners` is unsuitable: it needs a flat, fully visible board of known size.
- **v2 (F35):** a single-class EfficientDet-Lite0 (MediaPipe Model Maker, Apache-2.0), int8 on LiteRT, at 5–10 Hz.
  - Keep the texture confirmer, and train negatives on chequered banners.
  - Avoid Ultralytics YOLO: it is AGPL-3.0.
- **Data:**
  - Roboflow "checker flag detection" (CC BY 4.0).
  - Synthetic warps of chequered textures with motion blur.
  - Consented field footage.

**Proof points**

- EfficientDet-Lite0 int8 runs in **29.31 ms on CPU on a Pixel 6**. Source: https://developers.google.com/edge/mediapipe/solutions/vision/object_detector
- Model Maker custom training. Source: https://developers.google.com/edge/litert/libraries/modify/object_detection
- YOLOv8n int8 on a Dimensity 7020 takes 77–155 ms, and 618 ms through the NNAPI NPU path. Source: https://hexdocs.pm/nx_tflite_mob/yolo_walkthrough.html
- Dataset. Source: https://universe.roboflow.com/mae-148-team-2/checker-flag-detection
- LiteRT. Source: https://developers.google.com/edge/litert/android

**Risks**

- Small, furled, back-lit or occluded flags. Use hysteresis on the absence timeout.
- No public benchmark exists.

**Spike:** compare the texture detector with fine-tuned EfficientDet on about 300 labelled frames.

## E. Finish-line crossing detection

**Recommendation**

- **MVP:** a virtual photo finish, using slit-scan.
  - Each frame, sample 1–3 px along the user line and append it to a strip image, with x as time and y as position along the line.
  - Keep a running-median background per row.
  - A crossing is a foreground run. The leading edge at wheel height marks the front tyre.
  - Onsets in the occupancy signal trigger the beep and the keyframe.
  - Cost is O(H) per frame.
- **Bunches:** split them using wheel-height bands, vertical gradient breaks and a minimum inter-onset time. Counts are **suggestions** that the commissaire confirms with ±1 frame stepping.
- **v2 (F36):** bicycle/person detection with ByteTrack-style tracking in post-process, and front-wheel edge refinement.
- Stabilise by re-detecting the white line every N frames.
- **Auto white line:**
  1. HSV/Lab threshold for white and low saturation.
  2. Canny edges.
  3. `HoughLinesP` within ±20° of vertical.
  4. Score each line by length and whiteness.
  5. Show the best line as an editable overlay.

**Proof points**

- Photo finish and strip photography principles.
  - https://en.wikipedia.org/wiki/Photo_finish
  - https://en.wikipedia.org/wiki/Strip_photography
  - https://petapixel.com/2012/06/26/finish-line-cameras-and-slit-scan-photography/
  - https://zone4.ca/about/rapidcam/
- Resolution at 45 km/h:
  - **240 fps:** 4.2 ms per frame, about 5 cm of travel.
  - **60 fps:** about 21 cm of travel.

**Risks**

- Shadows.
- People standing on the line.
- Blur at 60 fps.
- Overlapping wheels.

**Spike:** label front-wheel crossing frames in 10 bunch sprints, then measure count error and frame error.

## F. Video persistence and frame-accurate review

**Recommendation**

- **Writing**
  - Encode H.264/HEVC. A custom `MediaCodec` path, needed post-MVP, can set `KEY_I_FRAME_INTERVAL` to 0.5–1 s. CameraX Recorder does not expose GOP, which is acceptable at 60 fps for the MVP.
  - One MP4 per acquisition, in app-specific storage, with optional export to MediaStore.
  - A sidecar `acq_<uuid>.json` holding:
    - device and camera metadata, fps and line geometry;
    - `firstFramePtsUs`;
    - crossings `{ptsUs, frameIndex, confidence, source}`;
    - flag intervals and app version.
  - **The time base is always PTS, never wall clock.**
- **Review**
  - Media3 ExoPlayer with `SeekParameters.EXACT`.
  - Scrubbing mode (`setScrubbingModeEnabled`, Media3 1.8+).
  - ±1 frame = seek to `pts ± 1/fps`.
  - A finish "loupe" decodes ±30 frames with `MediaCodec` or Media3 1.9 `FrameExtractor` into a bitmap ring.
  - Pinch zoom with `graphicsLayer` and `detectTransformGestures`.
  - `MediaMetadataRetriever` is for thumbnails only.
- **Storage**

  | Mode | Bitrate | Per minute |
  |---|---|---|
  | 1080p60 HEVC | 20–30 Mbps | 150–225 MB |
  | 1080p120 | 40–60 Mbps | 300–450 MB |

  - Guard free space and auto-purge.

**Proof points**

- Scrubbing and exact seeks.
  - https://developer.android.com/media/media3/exoplayer/scrubbing
  - https://developer.android.com/reference/androidx/media3/exoplayer/SeekParameters
- Media3 1.9 `FrameExtractor`. Source: https://developer.android.com/jetpack/androidx/releases/media3

**Risks**

- Slow-motion mode rescales PTS, so use `isSlowMotionEnabled=false`.
- Variable frame rate in low light. Always seek by PTS.

## G. Libraries

| Concern | Choice |
|---|---|
| Camera | CameraX 1.7.0 (core, camera2, lifecycle, video, compose); Camera2Interop V2 |
| Encoding | MediaCodec + Media3 Muxer |
| Playback | Media3 ExoPlayer 1.9+, media3-ui-compose |
| ML runtime | LiteRT `com.google.ai.edge.litert:litert` 2.x (+ GPU) |
| Audio ML | YAMNet `.tflite` on LiteRT (embeddings), or MediaPipe `tasks-audio` (scores only) |
| Vision ML | MediaPipe `tasks-vision` ObjectDetector (custom) or raw LiteRT |
| Classical CV | `org.opencv:opencv` 4.12+ (Maven Central) |
| State | Pure-Kotlin reducer + `StateFlow` in the FGS |
| UI | Compose, `CameraXViewfinder`, `Canvas` overlay |
| DI and logging | Hilt or Koin; Timber |

**Notes**

- Pin MediaPipe versions: a legacy `0.20230731` artifact sorts above 0.10.x.
- Every native library must be 16 KB page aligned (AGP 8.5.1+). Verify in APK Analyzer.
- Native code adds 30–60 MB. Ship arm64 splits, and consider dropping MediaPipe if LiteRT alone is enough.

**Sources**

- https://docs.opencv.org/4.x/d5/df8/tutorial_dev_with_OCV_on_Android.html
- https://central.sonatype.com/artifact/org.opencv/opencv
- https://developers.google.com/edge/litert/android
- https://developer.android.com/guide/practices/page-sizes

## H. Testability without real hardware

- **Interfaces:**
  - `AudioSource: Flow<PcmChunk>`, implemented by Mic and WavFile sources.
  - `FrameSource: Flow<Frame(ptsNs, luma, w, h, stride)>`, implemented by CameraX, GlTap, VideoFile (MediaExtractor+MediaCodec) and ImageSequence sources.
  - `Clock` and `BeepSink`.
- **Pure-Kotlin JVM detector modules:**
  - JUnit with `kotlinx-coroutines-test` virtual time.
  - OpenCV on the JVM through `org.openpnp:opencv` (`nu.pattern.OpenCV.loadShared()`). Pin the version.
  - The LiteRT/TF desktop runtime for models.
- **Golden fixtures** with labels, and metrics tests with thresholds.
- **Robolectric** for service and notification wiring and Compose logic. **Roborazzi** for screenshot evidence on the JVM.
- **Emulator:**
  - Gradle Managed Devices, or `reactivecircus/android-emulator-runner` on `ubuntu-latest` with KVM.
  - Replay fixtures through `VideoFileFrameSource`.
  - Camera smoke tests use `-camera-back emulated` (virtual scene with custom poster images), or `webcamN` through v4l2loopback and ffmpeg.
- **No high-speed capture on the emulator.** Use Firebase Test Lab for that.
- **In-app Replay mode** (F24).

**Sources**

- https://developer.android.com/studio/run/emulator-use-camera
- https://developer.android.com/studio/run/emulator-commandline
- https://github.com/ReactiveCircus/android-emulator-runner
- https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/
- https://github.com/openpnp/opencv
- https://developer.android.com/studio/test/gradle-managed-devices

## I. Bug reporting to GitHub issues

- **Backend:** a Cloudflare Worker acting as a GitHub App with `issues: write` only.
  - It signs a JWT and exchanges it for an installation token (valid 1 hour).
  - It calls `POST /repos/{o}/{r}/issues`.
  - It is protected by Play Integrity / App Check, rate limits and size caps.
- **Attachments:** GitHub has no issue-attachment API.
  - Upload to **private R2** via presigned PUT URLs, then link the files from the issue.
  - Keep the issue body under about 65k characters.
  - Never put user video in public release assets.
  - Trim or downscale clips, and get explicit consent.
- **Traces:**
  - Timber file tree (5 × 2 MB).
  - JSONL event log of state, decimated detector scores, camera config, thermal state and battery.
  - `ApplicationExitInfo`.
  - `logcat -d --pid`.
  - Capability dump.
  - Sidecar JSON.

**Sources**

- https://github.com/orgs/community/discussions/46951
- https://github.com/gr2m/cloudflare-worker-github-app-example
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation

**Risks:** abuse of a public endpoint, GDPR/consent for footage of identifiable people, storage cost.

## Top technical risks (ordered)

1. Process or camera survival while locked on OEM devices.
2. Bell false positives and negatives in real acoustics.
3. No analysis stream in high-speed sessions, and patchy 120/240 fps support.
4. Separating bunched riders.
5. Chequered flag robustness, with no public dataset.
6. Correctness of the pre-roll ring buffer.
7. Thermal, storage and battery limits.
8. Platform drift: Android 17 audio, Play wake-lock vitals, 16 KB pages, yearly targetSdk bumps.
9. Privacy and abuse of the bug-report endpoint.
