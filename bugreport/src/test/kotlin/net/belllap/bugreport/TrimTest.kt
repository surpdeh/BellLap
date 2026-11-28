package net.belllap.bugreport

import net.belllap.core.model.CrossingSource
import net.belllap.core.model.FlagInterval
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class TrimTest {
    private val sec = 1_000_000L

    @Test
    fun windowIsPlusMinusFiveSeconds() {
        assertEquals(TrimWindow(15 * sec, 25 * sec), TrimWindow.around(20 * sec, 60 * sec))
        assertEquals(10 * sec, TrimWindow.around(20 * sec, 60 * sec).durationUs)
    }

    @Test
    fun windowIsClampedToTheClip() {
        assertEquals(TrimWindow(0, 8 * sec), TrimWindow.around(3 * sec, 8 * sec))
        assertEquals(TrimWindow(54 * sec, 60 * sec), TrimWindow.around(59 * sec, 60 * sec))
        assertEquals(TrimWindow(0, 4 * sec), TrimWindow.around(2 * sec, 4 * sec))
        assertEquals(TrimWindow(55 * sec, 60 * sec), TrimWindow.around(500 * sec, 60 * sec))
        assertEquals(TrimWindow(0, 5 * sec), TrimWindow.around(-3 * sec, 60 * sec))
    }

    @Test
    fun candidatesAreRelativeToTheFirstFrameAndSorted() {
        val first = 900 * sec
        val sidecar = Sidecar(
            id = "a",
            name = "Finish 1",
            videoFile = "acq_a.mp4",
            device = SidecarDevice("Google", "Pixel", 36),
            fps = 30,
            width = 1920,
            height = 1080,
            firstFramePtsUs = first,
            crossings = listOf(SidecarCrossing(first + 12 * sec, 360, 0.9f, CrossingSource.LIVE)),
            flagIntervals = listOf(
                FlagInterval(first + 8 * sec, first + 14 * sec),
                FlagInterval(first - sec, first + sec),
            ),
            appVersion = "0.1.0",
        )

        val candidates = trimCandidates(sidecar)

        assertEquals(
            listOf("Flag 2 gone" to sec, "Flag 1 seen" to 8 * sec, "Crossing 1" to 12 * sec, "Flag 1 gone" to 14 * sec),
            candidates.map { it.label to it.centerUs },
        )
    }
}
