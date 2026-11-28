package net.belllap.core.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertsTest {
    private class Vol(override val max: Int, start: Int) : StreamVolume {
        val writes = ArrayList<Int>()
        override var current: Int = start
            set(value) {
                field = value
                writes += value
            }
    }

    @Test
    fun effectsMapToCues() {
        assertEquals(Cue.ARMED_BEEPS, Effect.PlayArmedBeeps.cueOrNull())
        assertEquals(Cue.CROSSING_BEEP, Effect.PlayCrossingBeep.cueOrNull())
        assertEquals(Cue.STAND_DOWN_CHIME, Effect.PlayStandDownChime.cueOrNull())
        assertNull(Effect.StartRecording.cueOrNull())
        assertNull(Effect.Log("x").cueOrNull())
    }

    @Test
    fun fakeSinkRecordsInOrder() {
        val sink = FakeBeepSink()
        sink.play(Cue.CROSSING_BEEP)
        sink.play(Cue.ARMED_BEEPS)
        assertEquals(listOf(Cue.CROSSING_BEEP, Cue.ARMED_BEEPS), sink.played)
    }

    @Test
    fun raisesToMaximumAndRestores() {
        val v = Vol(15, 4)
        val g = VolumeGuard(v)
        g.acquire()
        assertEquals(15, v.current)
        g.release()
        assertEquals(4, v.current)
    }

    @Test
    fun overlappingHoldersKeepTheFirstSavedLevelAndRestoreOnLastRelease() {
        val v = Vol(15, 4)
        val g = VolumeGuard(v)
        g.acquire()
        g.acquire()
        g.release()
        assertEquals("still held", 15, v.current)
        g.release()
        assertEquals(4, v.current)
    }

    @Test
    fun unbalancedReleaseIsANoOp() {
        val v = Vol(15, 4)
        val g = VolumeGuard(v)
        g.release()
        assertEquals(4, v.current)
        assertEquals(emptyList<Int>(), v.writes)
        g.acquire()
        g.release()
        g.release()
        assertEquals(4, v.current)
    }

    @Test
    fun percentRoundsToTheNearestStepAndClamps() {
        val v = Vol(15, 0)
        val g = VolumeGuard(v)
        g.acquire(50)
        assertEquals(8, v.current)
        g.release()
        g.acquire(0)
        assertEquals(0, v.current)
        g.release()
        g.acquire(250)
        assertEquals(15, v.current)
        g.release()
        g.acquire(-5)
        assertEquals(0, v.current)
    }

    @Test
    fun restoresWhenTheBlockThrows() {
        val v = Vol(15, 6)
        val g = VolumeGuard(v)
        try {
            g.withVolume { error("boom") }
        } catch (_: IllegalStateException) {
        }
        assertEquals(6, v.current)
        assertEquals("usable again", 7, g.withVolume { 7 })
    }

    @Test
    fun savedLevelIsRestoredEvenIfTheUserChangedItMeanwhile() {
        val v = Vol(15, 4)
        val g = VolumeGuard(v)
        g.acquire()
        v.current = 9
        g.release()
        assertEquals(4, v.current)
    }
}
