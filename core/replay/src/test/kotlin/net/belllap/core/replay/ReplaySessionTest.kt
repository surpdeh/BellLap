package net.belllap.core.replay

import kotlinx.coroutines.runBlocking
import net.belllap.audio.source.PcmAudioSource
import net.belllap.core.statemachine.Effect
import net.belllap.core.statemachine.State
import net.belllap.vision.core.Frame
import net.belllap.vision.core.InMemoryFrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySessionTest {
    private val sec = 1_000_000_000L
    private val rate = 8_000

    private class Recorder : ReplayListener {
        val transitions = mutableListOf<String>()
        val effects = mutableListOf<Effect>()

        override fun onTransition(from: State, to: State, trigger: String, ptsNs: Long) {
            transitions += "${from.label()}>${to.label()}:$trigger@${ptsNs / 1_000_000}"
        }

        override fun onEffect(effect: Effect, ptsNs: Long) {
            effects += effect
        }
    }

    private fun frames(seconds: Int) = InMemoryFrameSource(
        (0 until seconds * 10).map { Frame(it * sec / 10, 2, 2, 2, ByteArray(4)) },
    )

    private fun run(speed: ReplaySpeed = ReplaySpeed.FAST): Recorder {
        val rec = Recorder()
        val detectors = LabelDrivenDetectors.of(
            bellOnsetsNs = listOf(2 * sec),
            flagIntervals = listOf(15 * sec until 20 * sec),
            crossingsNs = listOf(16 * sec, 17 * sec),
        )
        val audio = PcmAudioSource(ShortArray(rate * 25), rate, chunkMs = 20)
        runBlocking { ReplaySession(audio, frames(25), detectors, rec).run(speed) }
        return rec
    }

    @Test
    fun replaysTheFullEventSequenceOnPts() {
        val t = run().transitions.map { it.substringBefore('@') }
        assertEquals(
            listOf(
                "SETUP>STANDBY:StandbyRequested",
                "STANDBY>ARMING:BellDetected",
                "ARMING>ARMED:ArmDelayElapsed",
                "ARMED>ACQUISITION:FlagSeen",
                "ACQUISITION>ACQUISITION:FlagLost",
                "ACQUISITION>ARMED:Stop",
                "ARMED>SETUP:Stop",
            ),
            t,
        )
    }

    @Test
    fun armDelayAndFlagTimeoutAreMeasuredInPts() {
        val t = run().transitions
        assertTrue(t.toString(), t.any { it.startsWith("ARMING>ARMED") && it.endsWith("@12000") })
        assertTrue(t.toString(), t.any { it.contains("FlagSeen") && it.endsWith("@15000") })
        // Flag lost at 20 s; the 15 s absence timeout would fire at 35 s, past the 25 s clip, so Stop ends it.
        assertTrue(t.toString(), t.none { it.contains("FlagAbsentTimeout") })
    }

    @Test
    fun crossingsBeepAndRecordingStarts() {
        val e = run().effects
        assertEquals(2, e.count { it == Effect.PlayCrossingBeep })
        assertEquals(1, e.count { it == Effect.StartRecording })
        assertEquals(1, e.count { it == Effect.StopRecording })
    }

    @Test
    fun noDetectorsMeansStandbyUntilStop() {
        val rec = Recorder()
        val audio = PcmAudioSource(ShortArray(rate * 5), rate)
        runBlocking { ReplaySession(audio, frames(5), Detectors.NONE, rec).run() }
        assertEquals(
            listOf("SETUP>STANDBY:StandbyRequested", "STANDBY>SETUP:Stop"),
            rec.transitions.map { it.substringBefore('@') },
        )
    }
}
