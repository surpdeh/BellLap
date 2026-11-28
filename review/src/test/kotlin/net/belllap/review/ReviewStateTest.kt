package net.belllap.review

import net.belllap.core.model.CrossingSource
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.core.model.Vec2
import net.belllap.core.model.ZoomPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewStateTest {
    private val seeks = mutableListOf<Long>()
    private val saved = mutableListOf<Sidecar>()

    private fun crossing(frame: Int, source: CrossingSource = CrossingSource.REFINED) =
        SidecarCrossing(frame * 1_000_000L / 30, frame, 0.8f, source)

    private fun sidecar(vararg frames: Int) = Sidecar(
        id = "a",
        name = "n",
        videoFile = "acq_a.mp4",
        device = SidecarDevice("G", "P", 36),
        fps = 30,
        width = 640,
        height = 480,
        firstFramePtsUs = 0,
        crossings = frames.map { crossing(it) },
        appVersion = "t",
    )

    // A 10 s clip at 30 fps: frames 0..299.
    private fun state(vararg frames: Int) =
        ReviewState(sidecar(*frames), 10_000_000L, { seeks += it }, { saved += it })

    @Test
    fun opensOnTheFirstFinisher() {
        val s = state(60, 120)
        assertEquals(60, s.frameIndex)
        assertEquals(0, s.selected)
        assertEquals(s.clock.positionUs(60), seeks.last())
    }

    @Test
    fun opensOnFrameZeroWithoutMarks() {
        val s = state()
        assertEquals(0, s.frameIndex)
        assertNull(s.selected)
    }

    @Test
    fun stepsByOneFrameBySeekingToThePositionOfThatFrame() {
        val s = state()
        s.step(1)
        s.step(1)
        s.step(-1)
        assertEquals(1, s.frameIndex)
        assertEquals(s.clock.positionUs(1), seeks.last())
    }

    @Test
    fun stepDoesNotGoPastTheEnds() {
        val s = state()
        s.step(-1)
        assertEquals(0, s.frameIndex)
        s.scrubTo(1f)
        s.step(1)
        assertEquals(299, s.frameIndex)
    }

    @Test
    fun previousAndNextFinisherSelectAndSeek() {
        val s = state(30, 90, 150)
        s.nextFinisher()
        assertEquals(1, s.selected)
        assertEquals(90, s.frameIndex)
        s.nextFinisher()
        s.nextFinisher()
        assertEquals(2, s.selected)
        s.previousFinisher()
        assertEquals(1, s.selected)
        s.step(5)
        s.previousFinisher()
        assertEquals(1, s.selected)
        assertEquals(90, s.frameIndex)
    }

    @Test
    fun zoomAndPanSurviveSteppingAndJumping() {
        val s = state(30, 90)
        s.applyGesture(Vec2(100f, 100f), Vec2(10f, 5f), 3f, Vec2(400f, 300f))
        val zoom = s.zoomPan
        assertEquals(3f, zoom.scale, 0.001f)
        s.step(1)
        s.nextFinisher()
        s.scrubTo(0.5f)
        assertEquals(zoom, s.zoomPan)
        assertEquals(ZoomPan(), state().zoomPan)
    }

    @Test
    fun addMarkHereAddsAManualMarkAndSelectsIt() {
        val s = state(30, 150)
        s.scrubTo(100f / 299)
        s.addMarkHere()
        assertEquals(listOf(30, 100, 150), s.sidecar.crossings.map { it.frameIndex })
        assertEquals(1, s.selected)
        assertEquals(CrossingSource.MANUAL, s.sidecar.crossings[1].source)
        assertEquals(s.sidecar, saved.last())
    }

    @Test
    fun addMarkOnAMarkedFrameJustSelectsIt() {
        val s = state(30, 90)
        s.step(60)
        s.addMarkHere()
        assertEquals(2, s.sidecar.crossings.size)
        assertEquals(1, s.selected)
        assertEquals(0, saved.size)
    }

    @Test
    fun moveSelectedHereMovesTheMarkAndKeepsItSelected() {
        val s = state(30, 90)
        s.step(10)
        s.moveSelectedHere()
        assertEquals(listOf(40, 90), s.sidecar.crossings.map { it.frameIndex })
        assertEquals(0, s.selected)
        s.step(100)
        s.moveSelectedHere()
        assertEquals(listOf(90, 140), s.sidecar.crossings.map { it.frameIndex })
        assertEquals(1, s.selected)
        assertEquals(2, saved.size)
    }

    @Test
    fun moveOntoAnotherMarkIsRefused() {
        val s = state(30, 90)
        s.step(60)
        s.moveSelectedHere()
        assertEquals(listOf(30, 90), s.sidecar.crossings.map { it.frameIndex })
        assertEquals(0, saved.size)
    }

    @Test
    fun deleteSelectedRemovesItAndClearsTheSelection() {
        val s = state(30, 90)
        s.deleteSelected()
        assertEquals(listOf(90), s.sidecar.crossings.map { it.frameIndex })
        assertNull(s.selected)
        s.deleteSelected()
        assertEquals(1, saved.size)
    }

    @Test
    fun editsWithoutASelectionDoNothing() {
        val s = state(30)
        s.selectMark(null)
        s.moveSelectedHere()
        s.deleteSelected()
        assertEquals(0, saved.size)
    }

    @Test
    fun positionFromThePlayerUpdatesTheFrame() {
        val s = state()
        s.onPosition(s.clock.positionUs(42))
        assertEquals(42, s.frameIndex)
    }

    @Test
    fun durationBoundsTheClip() {
        val s = ReviewState(sidecar(), null, { seeks += it }, { saved += it })
        s.onDuration(2_000_000L)
        s.scrubTo(1f)
        assertEquals(59, s.frameIndex)
    }
}
