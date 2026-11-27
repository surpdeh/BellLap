package net.belllap.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.belllap.audio.bell.AcceptAllClassifier
import net.belllap.audio.bell.BellTrace
import net.belllap.audio.source.AudioSource
import net.belllap.audio.source.PcmAudioSource
import net.belllap.audio.source.PcmChunk
import net.belllap.core.model.CaptureSettings
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

    private val log = object : SessionLog {
        override fun transition(from: State, to: State, trigger: String) {
            transitions += "$from->$to ($trigger)"
        }

        override fun bell(trace: BellTrace) {
            traces += trace
        }
    }

    private fun controller(audio: AudioSource) = SessionController(
        scope = scope,
        nowMs = { clockMs },
        openAudio = {
            opened++
            audio
        },
        classifier = { AcceptAllClassifier },
        log = log,
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

    @Test
    fun bellNowArmsByHandAndTheArmDelayEndsInArmed() = runBlocking {
        val c = controller(silence)
        c.updateSettings(CaptureSettings(bellAutomation = false))
        c.standby()
        assertEquals(State.Standby, c.state.value)
        assertFalse(c.bell.value.listening)
        assertEquals(0, opened)
        c.bellNow()
        assertEquals(State.Arming, c.state.value)
        clockMs = 10_000 // the timer fires on the injected clock, not the wall clock
        withTimeout(5_000) { c.state.first { it == State.Armed } }
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
}
