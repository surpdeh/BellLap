package net.belllap.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import net.belllap.core.model.ScreenLockSelfTest
import net.belllap.core.model.SelfTestMeasurements
import net.belllap.core.model.SelfTestVerdict
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State

/** What the runner needs from the session and the phone. Fakes stand in for both in JVM tests. */
interface SelfTestHost {
    val state: StateFlow<State>

    /** The recorder's latest problem, or null. */
    val notice: StateFlow<String?>

    fun dispatch(event: Event, trigger: String)

    /** PTS of the newest camera frame in microseconds, or null while none has arrived. */
    fun framePtsUs(): Long?

    /** Whether the screen is on and interactive. */
    fun screenOn(): Boolean
}

/** Where a self-test run is, for the settings screen. */
sealed interface SelfTestStatus {
    data object Idle : SelfTestStatus

    /** Lock the phone now; the simulated bell fires in [secondsLeft] s. */
    data class Countdown(val secondsLeft: Int) : SelfTestStatus

    data object Running : SelfTestStatus

    data class Done(val verdict: SelfTestVerdict) : SelfTestStatus
}

/**
 * Drives the F33 self-test through the real session: STANDBY, a countdown in which the user locks the phone, a
 * simulated bell, then the camera must open and record [ScreenLockSelfTest.RECORD_MS] of PTS time. Every step
 * after the bell runs with the screen off, so the same path a real locked bell takes is exercised. Forced Doze
 * (`adb shell dumpsys deviceidle force-idle`) during the countdown makes it the Tier 3 run.
 */
class ScreenLockSelfTestRunner(
    private val host: SelfTestHost,
    private val onStatus: (SelfTestStatus) -> Unit,
    private val lockCountdownS: Int = LOCK_COUNTDOWN_S,
) {
    suspend fun run(): SelfTestVerdict {
        host.dispatch(Event.StandbyRequested, "selftest")
        for (left in lockCountdownS downTo 1) {
            onStatus(SelfTestStatus.Countdown(left))
            delay(MS_PER_S)
        }
        onStatus(SelfTestStatus.Running)
        var m = SelfTestMeasurements(screenLockedAtBell = !host.screenOn())
        if (m.screenLockedAtBell) m = measureAfterBell(m)
        // Always leave the session idle, whatever happened.
        host.dispatch(Event.Stop, "selftest")
        return ScreenLockSelfTest.judge(m).also { onStatus(SelfTestStatus.Done(it)) }
    }

    /** Each step returns the updated measurements, or null when it failed, which ends the run at that step. */
    private suspend fun measureAfterBell(start: SelfTestMeasurements): SelfTestMeasurements {
        // A frame left over from the setup preview must not count, so only a changed PTS shows a camera that opened.
        val staleFrame = host.framePtsUs()
        host.dispatch(Event.BellDetected, "selftest")
        val steps = listOf<suspend (SelfTestMeasurements) -> SelfTestMeasurements?>(
            { m -> m.copy(armed = true).takeIf { waitFor(ARM_TIMEOUT_MS) { host.state.value == State.Armed } } },
            { m ->
                m.copy(frameSeen = true).takeIf {
                    waitFor(CAMERA_TIMEOUT_MS) { host.framePtsUs().let { it != null && it != staleFrame } }
                }
            },
            ::record,
        )
        var m = start
        for (step in steps) m = step(m) ?: break
        return m
    }

    private suspend fun record(m: SelfTestMeasurements): SelfTestMeasurements? {
        host.dispatch(Event.RecordNow, "selftest")
        val started = waitFor(RECORD_START_TIMEOUT_MS) { host.state.value is State.Acquisition }
        val startPts = host.framePtsUs()
        if (!started || host.notice.value != null || startPts == null) return null
        delay(ScreenLockSelfTest.RECORD_MS)
        val endPts = host.framePtsUs() ?: startPts
        return m.copy(recordingStarted = true, recordedMs = (endPts - startPts) / US_PER_MS)
    }

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    companion object {
        const val LOCK_COUNTDOWN_S = 30
        private const val MS_PER_S = 1_000L
        private const val US_PER_MS = 1_000L
        private const val POLL_MS = 100L

        /** ARMING lasts 10 s, so the arm timeout leaves a margin for a slow wake. */
        private const val ARM_TIMEOUT_MS = 30_000L
        private const val CAMERA_TIMEOUT_MS = 10_000L
        private const val RECORD_START_TIMEOUT_MS = 3_000L
    }
}
