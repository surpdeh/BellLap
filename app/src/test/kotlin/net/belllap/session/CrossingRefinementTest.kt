package net.belllap.session

import net.belllap.core.model.CrossingSource
import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.core.model.SavedAcquisition
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.testing.fixtures.Fixtures
import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.testing.fixtures.SceneSpec
import net.belllap.vision.core.Frame
import net.belllap.vision.core.VideoDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrossingRefinementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = Fixtures.crossings()
    private val line = SceneRenderer.LINE_X.toFloat().div(SceneSpec.WIDTH).let { x ->
        FinishLine(NormPoint(x, SceneRenderer.ROAD_TOP.toFloat() / SceneSpec.HEIGHT), NormPoint(x, 1f))
    }

    /** Decodes the fixture frames, with the container clock starting at [startNs] like a decoded MP4. */
    private class FakeDecoder(private val frames: List<Frame>, private val startNs: Long = 0L) : VideoDecoder {
        override fun decode(file: File): Sequence<Frame> = frames.asSequence().map {
            Frame(it.ptsNs + startNs, it.width, it.height, it.stride, it.luma)
        }
    }

    private fun saved(sidecar: Sidecar): SavedAcquisition {
        val json = tmp.newFile("acq_${sidecar.id}.json").apply { writeText(sidecar.encode()) }
        return SavedAcquisition(sidecar.id, tmp.newFile("acq_${sidecar.id}.mp4"), json)
    }

    private fun sidecar(crossings: List<SidecarCrossing>, withLine: Boolean = true) = Sidecar(
        id = "a", name = "n", videoFile = "acq_a.mp4", device = SidecarDevice("Acme", "Phone", 36), fps = 30,
        width = SceneSpec.WIDTH, height = SceneSpec.HEIGHT, line = line.takeIf { withLine },
        firstFramePtsUs = CAMERA_START_US, crossings = crossings, appVersion = "9.9",
    )

    @Test
    fun refinedCrossingsReplaceLiveOnesOnTheCameraClockAndKeepManualEdits() {
        val live = SidecarCrossing(CAMERA_START_US + 1L, 0, 0.5f, CrossingSource.LIVE)
        val manual = SidecarCrossing(CAMERA_START_US + 2L, 0, 1f, CrossingSource.MANUAL)
        val file = saved(sidecar(listOf(live, manual)))
        val result = CrossingRefinement(FakeDecoder(fixture.frames!!, startNs = 7_000_000L)).refine(file)!!
        val refined = result.crossings.filter { it.source == CrossingSource.REFINED }
        val labels = fixture.labels.crossings
        assertEquals(labels.map { it.frame }, refined.map { it.frameIndex })
        assertEquals(labels.map { CAMERA_START_US + it.ptsNs / NS_PER_US }, refined.map { it.ptsUs })
        assertEquals(listOf(manual), result.crossings.filter { it.source == CrossingSource.MANUAL })
        assertEquals(result, Sidecar.decode(file.sidecar.readText()))
    }

    @Test
    fun noLineOrNoFramesLeavesTheSidecarAlone() {
        val noLine = saved(sidecar(emptyList(), withLine = false))
        assertNull(CrossingRefinement(FakeDecoder(fixture.frames!!)).refine(noLine))
        val live = SidecarCrossing(CAMERA_START_US, 0, 0.5f, CrossingSource.LIVE)
        val noFrames = SavedAcquisition(
            "b",
            tmp.newFile("b.mp4"),
            tmp.newFile("b.json").apply {
                writeText(sidecar(listOf(live)).encode())
            },
        )
        assertNull(CrossingRefinement(FakeDecoder(emptyList())).refine(noFrames))
        assertEquals(listOf(live), Sidecar.decode(noFrames.sidecar.readText()).crossings)
    }

    @Test
    fun aDecoderErrorPropagatesAndKeepsTheSidecar() {
        val live = SidecarCrossing(CAMERA_START_US, 0, 0.5f, CrossingSource.LIVE)
        val file = saved(sidecar(listOf(live)))
        val broken = object : VideoDecoder {
            override fun decode(file: File): Sequence<Frame> = error("codec died")
        }
        assertThrows(IllegalStateException::class.java) { CrossingRefinement(broken).refine(file) }
        assertEquals(listOf(live), Sidecar.decode(file.sidecar.readText()).crossings)
    }

    private companion object {
        const val CAMERA_START_US = 5_000_000L
        const val NS_PER_US = 1000L
    }
}
