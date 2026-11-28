package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewMarksTest {
    private val clock = FrameClock(fps = 30, firstFramePtsUs = 0, lastIndex = 299)

    private fun crossing(pts: Long, source: CrossingSource = CrossingSource.REFINED) =
        SidecarCrossing(pts, (pts * 30 / 1_000_000).toInt(), 0.8f, source)

    private val sidecar = Sidecar(
        id = "a",
        name = "n",
        videoFile = "acq_a.mp4",
        device = SidecarDevice("G", "P", 36),
        fps = 30,
        width = 640,
        height = 480,
        firstFramePtsUs = 0,
        crossings = listOf(crossing(1_000_000), crossing(3_000_000), crossing(5_000_000)),
        appVersion = "t",
    )

    @Test
    fun addedMarkIsManualAndKeepsTheListSorted() {
        val out = sidecar.withCrossingAdded(4_000_000, 120)
        assertEquals(listOf(1_000_000L, 3_000_000L, 4_000_000L, 5_000_000L), out.crossings.map { it.ptsUs })
        assertEquals(SidecarCrossing(4_000_000, 120, 1f, CrossingSource.MANUAL), out.crossings[2])
        assertEquals(3, sidecar.crossings.size)
    }

    @Test
    fun movedMarkBecomesManualAndResorts() {
        val out = sidecar.withCrossingMoved(0, 6_000_000, 180)
        assertEquals(listOf(3_000_000L, 5_000_000L, 6_000_000L), out.crossings.map { it.ptsUs })
        assertEquals(SidecarCrossing(6_000_000, 180, 1f, CrossingSource.MANUAL), out.crossings[2])
    }

    @Test
    fun removedMarkIsDropped() {
        assertEquals(listOf(1_000_000L, 5_000_000L), sidecar.withCrossingRemoved(1).crossings.map { it.ptsUs })
    }

    @Test
    fun outOfRangeIndexLeavesTheSidecarUntouched() {
        assertEquals(sidecar, sidecar.withCrossingMoved(9, 1, 0))
        assertEquals(sidecar, sidecar.withCrossingRemoved(-1))
    }

    @Test
    fun editedSidecarStillValidates() {
        assertEquals(emptyList<String>(), sidecar.withCrossingAdded(0, 0).validate())
    }

    @Test
    fun previousAndNextAreStrictAndNullAtTheEnds() {
        // Frames 30, 90 and 150 hold the marks.
        assertEquals(1, sidecar.nextCrossing(clock, 30))
        assertEquals(2, sidecar.nextCrossing(clock, 90))
        assertNull(sidecar.nextCrossing(clock, 150))
        assertEquals(0, sidecar.previousCrossing(clock, 31))
        assertNull(sidecar.previousCrossing(clock, 30))
        assertEquals(1, sidecar.previousCrossing(clock, 150))
    }

    @Test
    fun lookupIgnoresJitterBetweenTheMarkAndTheNominalFramePts() {
        val jittered = sidecar.copy(crossings = listOf(crossing(1_004_000), crossing(3_000_000)))
        assertEquals(1, jittered.nextCrossing(clock, 30))
        assertNull(jittered.previousCrossing(clock, 30))
    }

    @Test
    fun markAtFindsTheMarkOnAFrame() {
        assertEquals(1, sidecar.markAt(clock, 90))
        assertNull(sidecar.markAt(clock, 91))
    }
}
