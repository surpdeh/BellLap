package net.belllap.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.belllap.core.model.SelfTestFailure
import net.belllap.core.model.SelfTestVerdict
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenLockSelfTestRunnerTest {
    /** A session whose camera clock follows the virtual time, so recorded spans are exact. */
    private class FakeHost(private val scope: TestScope) : SelfTestHost {
        override val state = MutableStateFlow<State>(State.Setup)
        override val notice = MutableStateFlow<String?>(null)
        val events = ArrayList<Event>()
        var screenOn = false
        var cameraWorks = true
        var recorderRefuses = false
        var armsOnBell = true
        var framesStopAfterBellMs: Long? = null
        private var bellAt = 0L

        override fun dispatch(event: Event, trigger: String) {
            events += event
            when (event) {
                Event.StandbyRequested -> state.value = State.Standby
                Event.BellDetected -> {
                    bellAt = scope.currentTime
                    if (armsOnBell) state.value = State.Armed
                }
                Event.RecordNow -> {
                    if (recorderRefuses) notice.value = "The camera is not ready."
                    state.value = State.Acquisition(flagVisible = false)
                }
                Event.Stop -> state.value = State.Setup
                else -> Unit
            }
        }

        override fun framePtsUs(): Long? {
            if (!cameraWorks || state.value !is State.Armed && state.value !is State.Acquisition) return null
            val limit = framesStopAfterBellMs?.let { bellAt + it } ?: Long.MAX_VALUE
            return minOf(scope.currentTime, limit) * 1_000L
        }

        override fun screenOn() = screenOn
    }

    private fun run(configure: FakeHost.() -> Unit = {}): Pair<SelfTestVerdict, FakeHost> {
        var host: FakeHost? = null
        var verdict: SelfTestVerdict? = null
        runTest {
            val h = FakeHost(this).apply(configure)
            host = h
            verdict = async { ScreenLockSelfTestRunner(h, {}, lockCountdownS = 3).run() }.await()
        }
        return verdict!! to host!!
    }

    private fun failure(v: SelfTestVerdict) = (v as SelfTestVerdict.Failed).reason

    @Test
    fun lockedPhoneThatArmsAndRecordsFiveSecondsPasses() {
        val (verdict, host) = run()
        assertEquals(SelfTestVerdict.Passed, verdict)
        assertEquals(
            listOf(Event.StandbyRequested, Event.BellDetected, Event.RecordNow, Event.Stop),
            host.events,
        )
    }

    @Test
    fun screenStillOnFailsAndNeverFiresTheBell() {
        val (verdict, host) = run { screenOn = true }
        assertEquals(SelfTestFailure.SCREEN_NOT_LOCKED, failure(verdict))
        assertTrue(Event.BellDetected !in host.events)
        assertEquals(Event.Stop, host.events.last())
    }

    @Test
    fun sessionThatDoesNotArmFails() =
        assertEquals(SelfTestFailure.DID_NOT_ARM, failure(run { armsOnBell = false }.first))

    @Test
    fun cameraThatNeverDeliversFramesFails() =
        assertEquals(SelfTestFailure.CAMERA_DID_NOT_OPEN, failure(run { cameraWorks = false }.first))

    @Test
    fun recorderRefusalFails() =
        assertEquals(SelfTestFailure.RECORDING_REFUSED, failure(run { recorderRefuses = true }.first))

    @Test
    fun cameraFreezingMidRecordingFailsAsTooShort() {
        // Frames stop 2 s after the bell, so the recorded span cannot reach 5 s.
        val verdict = run { framesStopAfterBellMs = 2_000 }.first
        assertEquals(SelfTestFailure.RECORDING_TOO_SHORT, failure(verdict))
    }
}
