package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameClockTest {
    private val clock = FrameClock(fps = 30, firstFramePtsUs = 5_000_000L, lastIndex = 89)

    @Test
    fun seekPositionIsTheMiddleOfTheFrameInterval() {
        assertEquals(16_666L, clock.positionUs(0))
        assertEquals(1_016_666L, clock.positionUs(30))
    }

    @Test
    fun positionMapsBackToTheSameFrame() {
        for (i in 0..89) assertEquals(i, clock.indexAt(clock.positionUs(i)))
    }

    @Test
    fun indexAtClampsToTheClip() {
        assertEquals(0, clock.indexAt(-1))
        assertEquals(89, clock.indexAt(99_000_000))
    }

    @Test
    fun ptsMapsToTheNearestFrame() {
        assertEquals(0L, clock.ptsToPosition(5_000_000L))
        assertEquals(30, clock.indexOfPts(5_000_000L + 1_000_000L + 7_000L))
        assertEquals(31, clock.indexOfPts(5_000_000L + 1_000_000L + 20_000L))
        assertEquals(0, clock.indexOfPts(1_000L))
    }

    @Test
    fun framePtsRoundTripsThroughIndexOfPts() {
        for (i in 0..89) assertEquals(i, clock.indexOfPts(clock.ptsOf(i)))
        assertEquals(5_000_000L, clock.ptsOf(0))
        assertEquals(6_000_000L, clock.ptsOf(30))
    }

    @Test
    fun stepClampsAtBothEnds() {
        assertEquals(11, clock.step(10, 1))
        assertEquals(9, clock.step(10, -1))
        assertEquals(0, clock.step(2, -5))
        assertEquals(89, clock.step(88, 5))
    }

    @Test
    fun forDurationCountsWholeFrames() {
        val c = FrameClock.forDuration(fps = 30, firstFramePtsUs = 0, durationUs = 3_000_000L)
        assertEquals(89, c.lastIndex)
        assertEquals(0, FrameClock.forDuration(30, 0, 0).lastIndex)
    }

    @Test
    fun rejectsBadFps() {
        assertThrows(IllegalArgumentException::class.java) { FrameClock(0, 0) }
    }
}
