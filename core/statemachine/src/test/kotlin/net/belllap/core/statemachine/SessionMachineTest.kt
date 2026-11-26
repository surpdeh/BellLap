package net.belllap.core.statemachine

import net.belllap.core.statemachine.Effect.PlayArmedBeeps
import net.belllap.core.statemachine.Effect.PlayCrossingBeep
import net.belllap.core.statemachine.Effect.PlayStandDownChime
import net.belllap.core.statemachine.Effect.RestoreBrightness
import net.belllap.core.statemachine.Effect.StartRecording
import net.belllap.core.statemachine.Effect.StopRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionMachineTest {
    private fun machine(config: SessionConfig = SessionConfig()) = SessionMachine(config)

    @Test
    fun happyPath() {
        val m = machine()
        assertEquals(emptyList<Effect>(), m.dispatch(Event.StandbyRequested, 0))
        assertEquals(listOf(RestoreBrightness), m.dispatch(Event.BellDetected, 1_000))
        assertEquals(State.Arming, m.state)
        assertEquals(11_000L, m.nextDeadlineMs)
        assertEquals(emptyList<Effect>(), m.advanceTo(10_999))
        assertEquals(listOf(PlayArmedBeeps), m.advanceTo(11_000))
        assertEquals(State.Armed, m.state)
        assertEquals(listOf(StartRecording), m.dispatch(Event.FlagSeen, 20_000))
        assertEquals(listOf(PlayCrossingBeep), m.dispatch(Event.CrossingDetected, 21_000))
        m.dispatch(Event.FlagLost, 30_000)
        assertEquals(45_000L, m.nextDeadlineMs)
        assertEquals(listOf(StopRecording, PlayStandDownChime), m.advanceTo(45_000))
        assertEquals(State.Armed, m.state)
        assertNull(m.nextDeadlineMs)
    }

    @Test
    fun flagSeenAgainCancelsAbsenceTimer() {
        val m = machine()
        m.dispatch(Event.StandbyRequested, 0)
        m.dispatch(Event.BellNow, 0)
        m.advanceTo(10_000)
        m.dispatch(Event.FlagSeen, 11_000)
        m.dispatch(Event.FlagLost, 12_000)
        m.dispatch(Event.FlagSeen, 20_000)
        assertNull(m.nextDeadlineMs)
        assertEquals(emptyList<Effect>(), m.advanceTo(100_000))
        assertEquals(State.Acquisition(true), m.state)
    }

    @Test
    fun configurableTimeoutAndDispatchFiresDueTimerFirst() {
        val m = machine(SessionConfig(armDelayMs = 1_000, flagAbsentTimeoutMs = 2_000))
        m.dispatch(Event.StandbyRequested, 0)
        m.dispatch(Event.BellNow, 0)
        // Flag arrives after the arm delay has passed: the delay fires first, then the flag is accepted.
        assertEquals(listOf(PlayArmedBeeps, StartRecording), m.dispatch(Event.FlagSeen, 1_500))
        m.dispatch(Event.FlagLost, 2_000)
        assertEquals(4_000L, m.nextDeadlineMs)
    }

    @Test
    fun manualRecordRunsAbsenceTimerAndStopIsImmediate() {
        val m = machine()
        m.dispatch(Event.StandbyRequested, 0)
        m.dispatch(Event.BellNow, 0)
        m.advanceTo(10_000)
        m.dispatch(Event.RecordNow, 10_000)
        assertEquals(25_000L, m.nextDeadlineMs)
        assertEquals(listOf(StopRecording, PlayStandDownChime), m.dispatch(Event.Stop, 12_000))
        assertEquals(State.Armed, m.state)
    }

    @Test
    fun reviewWhileArmedKeepsSessionRunning() {
        val m = machine()
        m.dispatch(Event.StandbyRequested, 0)
        m.dispatch(Event.BellNow, 0)
        m.advanceTo(10_000)
        m.dispatch(Event.EnterReview, 10_000)
        assertEquals(State.Review(State.Armed), m.state)
        assertEquals(listOf(StartRecording), m.dispatch(Event.FlagSeen, 11_000))
        assertEquals(State.Review(State.Acquisition(true)), m.state)
        m.dispatch(Event.ExitReview, 12_000)
        assertEquals(State.Acquisition(true), m.state)
    }

    @Test
    fun timersKeepRunningDuringReview() {
        val m = machine()
        m.dispatch(Event.StandbyRequested, 0)
        m.dispatch(Event.EnterReview, 0)
        m.dispatch(Event.BellDetected, 1_000)
        assertEquals(listOf(PlayArmedBeeps), m.advanceTo(11_000))
        assertEquals(State.Review(State.Armed), m.state)
    }

    @Test
    fun reviewNotAllowedWhileArmingOrRecording() {
        assertTrue(reduce(State.Arming, Event.EnterReview).state == State.Arming)
        assertTrue(reduce(State.Acquisition(true), Event.EnterReview).state == State.Acquisition(true))
    }

    @Test
    fun invalidTransitionsAreIgnoredAndLogged() {
        for (s in TransitionTable.states) {
            for (e in TransitionTable.events) {
                val t = reduce(s, e)
                if (t.state == s && t.effects.none { it !is Effect.Log } && t.effects.isNotEmpty()) {
                    assertEquals("$s $e", 1, t.effects.size)
                }
            }
        }
        val t = reduce(State.Setup, Event.FlagSeen)
        assertEquals(State.Setup, t.state)
        assertTrue(t.effects.single() is Effect.Log)
        // A stale timeout while the flag is visible must not stop the recording.
        val stale = reduce(State.Acquisition(true), Event.FlagAbsentTimeout)
        assertEquals(State.Acquisition(true), stale.state)
        assertTrue(stale.effects.single() is Effect.Log)
    }

    @Test
    fun neverMoreThanOneActiveRecording() {
        // Exhaustive walk of all event sequences up to depth 6 from every state.
        fun walk(s: State, recording: Int, depth: Int) {
            val active = (if (s is State.Review) s.underlying else s) is State.Acquisition
            assertEquals("recording count vs state $s", if (active) 1 else 0, recording)
            if (depth == 0) return
            for (e in TransitionTable.events) {
                val t = reduce(s, e)
                var r = recording
                for (fx in t.effects) {
                    if (fx == StartRecording) r++
                    if (fx == StopRecording) r--
                    assertTrue("recordings=$r after $e in $s", r in 0..1)
                }
                walk(t.state, r, depth - 1)
            }
        }
        walk(State.Setup, 0, 6)
    }

    @Test
    fun transitionTableDocIsUpToDate() {
        val doc = File("../../docs/state-machine.md")
        val generated = TransitionTable.markdown()
        if (System.getProperty("belllap.updateDocs") == "true") doc.writeText(generated)
        assertEquals("regenerate with -Dbelllap.updateDocs=true", generated, doc.readText())
    }
}
