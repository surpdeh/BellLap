#!/usr/bin/env bash
# Runs inside android-emulator-runner. Records the screen while the instrumented tests run,
# then collects screenshots, the recording, logcat and the app event log into evidence/.
set -uo pipefail
mkdir -p evidence

# Make sure the display is on and unlocked, otherwise screenshots and recordings are black.
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
# The software-rendered emulator makes Pixel Launcher miss its ANR deadline; keep that dialog off screen.
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

adb logcat -c || true
adb shell screenrecord --time-limit 180 /data/local/tmp/e2e.mp4 &
REC_PID=$!

# Wait for the APK build that ci.yml started before the emulator booted, and show its log.
if [ -f prebuild.log ]; then
  while [ ! -f prebuild.status ]; do sleep 2; done
  echo "== background APK build (exit $(cat prebuild.status)) =="
  cat prebuild.log
  echo "== end background APK build =="
fi

# Keep the APKs installed afterwards so run-as can pull files/evidence.
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
STATUS=$?

# SIGINT the on-device recorder (not just the adb client) so the MP4 is finalised.
adb shell pkill -INT screenrecord || true
wait "$REC_PID" 2>/dev/null || true
sleep 2
adb pull /data/local/tmp/e2e.mp4 evidence/e2e.mp4 || true
adb exec-out screencap -p > evidence/final-screen.png || true
# Screenshots that tests save to the app's files/evidence directory (see TestEvidence helper, F01).
# Pull each file with run-as + cat (the ATD image has no reliable tar) and log what happens.
APP=net.belllap.debug
echo "evidence files in app sandbox:"
adb shell run-as "$APP" ls files/evidence || echo "WARN: run-as failed or files/evidence missing (is $APP still installed?)"
for f in $(adb shell run-as "$APP" ls files/evidence 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$APP" cat "files/evidence/$f" > "evidence/$f" || echo "WARN: could not pull $f"
done
adb shell run-as net.belllap.debug cat files/logs/device-capabilities.json > evidence/device-capabilities-app.json 2>/dev/null || true
adb shell run-as net.belllap.debug cat files/logs/events.jsonl > evidence/events.jsonl 2>/dev/null || true
adb logcat -d > evidence/logcat.txt || true
# Surface the useful lines in the job log (the artifact is not always reachable).
echo "== logcat excerpt: crashes, camera and test failures =="
grep -E "FATAL EXCEPTION|AndroidRuntime|TestRunner:.*(failed|Exception|Error)|BellLapReplay" evidence/logcat.txt | cut -c1-260 | head -20 || true
echo "-- camera lines (first 60) --"
grep -E "BellLapCamera|CameraX|Camera2CameraImpl|CameraDevice|CameraCaptureSession|CameraService|Camera preview|SurfaceRequest" evidence/logcat.txt | grep -vE "AppsFilter|WindowManager|Transition|CameraValidator|^\S+ \S+ +[0-9]+ +[0-9]+ [A-Z] CameraX *: +(at |\.\.\.)|Caused by" | cut -c1-260 | head -80 || true
echo "-- thread stacks when the preview wait expired --"
grep "PreviewHang" evidence/logcat.txt | cut -c29-260 | head -120 || true
echo "-- camera service and HAL lines --"
grep -iE "cameraserver|CameraProviderManager|camera.provider|EmulatedCamera|goldfish|CameraManager" evidence/logcat.txt | grep -vE "AppsFilter|WindowManager|Transition" | cut -c1-240 | head -50 || true
echo "-- camera features and services --"
adb shell pm list features | grep -i camera || echo "no camera feature reported"
adb shell dumpsys media.camera | head -15 || true
echo "== end logcat excerpt =="

command -v ffmpeg >/dev/null || echo "WARN: ffmpeg not found, no GIF"
if command -v ffmpeg >/dev/null && [ -s evidence/e2e.mp4 ]; then
  ffmpeg -loglevel error -y -i evidence/e2e.mp4 -vf "fps=8,scale=360:-1:flags=lanczos" evidence/e2e.gif || true
fi
exit $STATUS
