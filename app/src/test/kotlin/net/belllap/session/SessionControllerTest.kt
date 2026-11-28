package net.belllap.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.belllap.audio.bell.AcceptAllClassifier
import net.belllap.audio.bell.BellTrace
import net.belllap.audio.source.AudioSource
import net.belllap.audio.source.PcmAudioSource
import net.belllap.audio.source.PcmChunk
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.VolumeKey
import net.belllap.core.model.VolumeKeyAction
import net.belllap.core.statemachine.Cue
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.FakeBeepSink
import net.belllap.core.statemachine.State
import net.belllap.testing.fixtures.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SessionControllerTest {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val transitions = Collections.synchronizedList(ArrayList<String>())
    private val traces = Collections.synchronizedList(ArrayList<BellTrace>())
    private var clockMs = 0L
    private var opened = 0
    private val beeps = FakeBeepSink()

    private val log = object : SessionLog {
        override fun transition(from: State, to: State, trigger: String) {
            transitions += "$from->$to ($trigger)"
        }

        override fun bell(trace: BellTrace) {
            traces += trace
        }
    }

    private fun controller(
        audio: AudioSource,
        scope: CoroutineScope = this.scope,
        nowMs: () -> Long = { clockMs },
    ) = SessionController(
        scope = scope,
        nowMs = nowMs,
        openAudio = {
            opened++
            audio
        },
        classifier = { AcceptAllClassifier },
        log = log,
        beeps = beeps,
    )

    private val silence = object : AudioSource {
        override val sampleRate = 16_000

        override fun chunks(): Flow<PcmChunk> = emptyFlow()
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aBellInTheFixtureMovesStandbyToArming() = runBlocking {
        val audio = Fixtures.race().audio!!
        val c = controller(PcmAudioSource(audio.samples, audio.sampleRate, 20))
        c.updateSettings(CaptureSettings())
        c.standby()
        withTimeout(30_000) { c.state.first { it == State.Arming } }
        assertTrue(transitions.toString(), transitions.last().endsWith("(bell:BellDetected)"))
        assertTrue(traces.any { it is BellTrace.Detected })
        assertFalse("listening stops once armed", c.bell.value.listening)
        assertEquals("stage0-only", c.bell.value.classifier)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bellNowArmsByHandAndTheArmDelayEndsInArmed() = runTest {
        // The arm-delay timer's delay and the machine's clock both run on virtual time, so the test is not a race
        // between the timer coroutine reading the clock and the test advancing it.
        val c = controller(silence, scope = backgroundScope, nowMs = { testScheduler.currentTime })
        c.updateSettings(CaptureSettings(bellAutomation = false))
        c.standby()
        assertEquals(State.Standby, c.state.value)
        assertFalse(c.bell.value.listening)
        assertEquals(0, opened)
        c.bellNow()
        assertEquals(State.Arming, c.state.value)
        advanceTimeBy(ARM_DELAY_MS - 1)
        runCurrent()
        assertEquals("still arming just before the delay ends", State.Arming, c.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(State.Armed, c.state.value)
        c.stop()
        assertEquals(State.Setup, c.state.value)
        assertEquals(
            listOf(
                "Setup->Standby (user:StandbyRequested)",
                "Standby->Arming (user:BellNow)",
                "Arming->Armed (timer)",
                "Armed->Setup (user:Stop)",
            ),
            transitions,
        )
    }

    @Test
    fun turningBellDetectionOnInStandbyStartsListening() = runBlocking {
        val c = controller(silence)
        c.updateSettings(CaptureSettings(bellAutomation = false))
        c.standby()
        c.updateSettings(CaptureSettings(bellAutomation = true))
        assertTrue(c.bell.value.listening)
        withTimeout(5_000) { while (opened == 0) Thread.sleep(5) }
        assertEquals(1, opened)
    }

    @Test
    fun volumeKeysAreIgnoredInSetupAndWhenUnmapped() = runBlocking {
        val c = controller(silence)
        c.updateSettings(CaptureSettings(bellAutomation = false, volumeUpAction = VolumeKeyAction.ARM))
        assertFalse("SETUP leaves the key to the system", c.volumeKey(VolumeKey.UP))
        assertEquals(State.Setup, c.state.value)
        c.standby()
        assertFalse("unmapped key is not swallowed", c.volumeKey(VolumeKey.DOWN))
        assertEquals(State.Standby, c.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun mappedVolumeKeysArmThenRecord() = runTest {
        val c = controller(silence, scope = backgroundScope, nowMs = { testScheduler.currentTime })
        c.updateSettings(
            CaptureSettings(
                bellAutomation = false,
                volumeUpAction = VolumeKeyAction.ARM,
                volumeDownAction = VolumeKeyAction.RECORD,
            ),
        )
        c.standby()
        assertTrue(c.volumeKey(VolumeKey.UP, repeat = true))
        assertEquals("a held key does nothing", State.Standby, c.state.value)
        assertTrue(c.volumeKey(VolumeKey.UP))
        assertEquals(State.Arming, c.state.value)
        assertTrue(transitions.last().endsWith("(volume_up:BellNow)"))
        assertTrue(c.volumeKey(VolumeKey.DOWN))
        assertEquals("record is ignored until armed", State.Arming, c.state.value)
        advanceTimeBy(ARM_DELAY_MS)
        runCurrent()
        assertTrue(c.volumeKey(VolumeKey.DOWN))
        assertEquals(State.Acquisition(flagVisible = false), c.state.value)
        assertTrue(transitions.last().endsWith("(volume_down:RecordNow)"))
    }

    /** Runs a full session on virtual time and returns the cues the sink heard. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.cuesForSession(settings: CaptureSettings): List<Cue> {
        val c = controller(silence, scope = backgroundScope, nowMs = { testScheduler.currentTime })
        c.updateSettings(settings.copy(bellAutomation = false))
        c.standby()
        c.bellNow()
        testScheduler.advanceTimeBy(ARM_DELAY_MS)
        testScheduler.runCurrent()
        c.dispatch(Event.FlagSeen, "test")
        testScheduler.advanceTimeBy(1_000)
        c.dispatch(Event.CrossingDetected, "test")
        c.stop()
        return beeps.played
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun allCuesPlayInOrderThroughTheSession() = runTest {
        assertEquals(
            listOf(Cue.ARMED_BEEPS, Cue.CROSSING_BEEP, Cue.STAND_DOWN_CHIME),
            cuesForSession(CaptureSettings()),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun armedBeepsWaitForTheArmDelay() = runTest {
        val c = controller(silence, scope = backgroundScope, nowMs = { testScheduler.currentTime })
        c.updateSettings(CaptureSettings(bellAutomation = false))
        c.standby()
        c.bellNow()
        advanceTimeBy(ARM_DELAY_MS - 1)
        runCurrent()
        assertEquals(emptyList<Cue>(), beeps.played)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(Cue.ARMED_BEEPS), beeps.played)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun armedBeepsOffPlaysTheOtherTwo() = runTest {
        assertEquals(
            listOf(Cue.CROSSING_BEEP, Cue.STAND_DOWN_CHIME),
            cuesForSession(CaptureSettings(armedBeeps = false)),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun crossingBeepsOffPlaysTheOtherTwo() = runTest {
        assertEquals(
            listOf(Cue.ARMED_BEEPS, Cue.STAND_DOWN_CHIME),
            cuesForSession(CaptureSettings(crossingBeeps = false)),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun standDownChimeOffPlaysTheOtherTwo() = runTest {
        assertEquals(
            listOf(Cue.ARMED_BEEPS, Cue.CROSSING_BEEP),
            cuesForSession(CaptureSettings(standDownChime = false)),
        )
    }

    @Test
    fun settingsGateEveryCue() {
        val none = CaptureSettings(armedBeeps = false, crossingBeeps = false, standDownChime = false)
        assertTrue(Cue.entries.none { none.allows(it) })
        assertTrue(Cue.entries.all { CaptureSettings().allows(it) })
    }

    @Test
    fun alertVolumeDefaultsToMaximumAndFollowsTheCustomPercent() {
        val c = controller(silence)
        assertEquals(100, c.alertVolumePercent)
        c.updateSettings(CaptureSettings(alertVolumePercent = 40))
        assertEquals(40, c.alertVolumePercent)
    }

    private companion object {
        const val ARM_DELAY_MS = 10_000L
    }
}
