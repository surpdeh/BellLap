#!/usr/bin/env bash
# Runs inside android-emulator-runner. Records the screen while the instrumented tests run,
# then collects screenshots, the recording, logcat and the app event log into evidence/.
set -uo pipefail
mkdir -p evidence

# Make sure the display is on and unlocked, otherwise screenshots and recordings are black.
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

adb logcat -c || true
adb shell screenrecord --time-limit 180 /data/local/tmp/e2e.mp4 &
REC_PID=$!

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
adb shell run-as net.belllap.debug cat files/logs/events.jsonl > evidence/events.jsonl 2>/dev/null || true
adb logcat -d > evidence/logcat.txt || true

command -v ffmpeg >/dev/null || echo "WARN: ffmpeg not found, no GIF"
if command -v ffmpeg >/dev/null && [ -s evidence/e2e.mp4 ]; then
  ffmpeg -loglevel error -y -i evidence/e2e.mp4 -vf "fps=8,scale=360:-1:flags=lanczos" evidence/e2e.gif || true
fi
exit $STATUS
