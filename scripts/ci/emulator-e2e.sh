#!/usr/bin/env bash
# Runs inside android-emulator-runner. Records the screen while the instrumented tests run,
# then collects screenshots, the recording, logcat and the app event log into evidence/.
set -uo pipefail
mkdir -p evidence

adb logcat -c || true
adb shell screenrecord --time-limit 180 /sdcard/e2e.mp4 &
REC_PID=$!

# Keep the APKs installed afterwards so run-as can pull files/evidence.
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
STATUS=$?

# SIGINT the on-device recorder (not just the adb client) so the MP4 is finalised.
adb shell pkill -INT screenrecord || true
wait "$REC_PID" 2>/dev/null || true
sleep 2
adb pull /sdcard/e2e.mp4 evidence/e2e.mp4 || true
adb exec-out screencap -p > evidence/final-screen.png || true
# Screenshots that tests save to the app's files/evidence directory (see TestEvidence helper, F01)
adb shell run-as net.belllap.debug sh -c 'cd files && tar cf - evidence 2>/dev/null' 2>/dev/null | tar xf - -C evidence 2>/dev/null || true
adb shell run-as net.belllap.debug cat files/logs/events.jsonl > evidence/events.jsonl 2>/dev/null || true
adb logcat -d > evidence/logcat.txt || true

if command -v ffmpeg >/dev/null && [ -s evidence/e2e.mp4 ]; then
  ffmpeg -loglevel error -y -i evidence/e2e.mp4 -vf "fps=8,scale=360:-1:flags=lanczos" evidence/e2e.gif || true
fi
exit $STATUS
