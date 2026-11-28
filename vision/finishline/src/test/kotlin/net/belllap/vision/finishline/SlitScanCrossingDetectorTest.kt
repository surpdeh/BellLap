package net.belllap.vision.finishline

import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.testing.fixtures.Fixtures
import net.belllap.testing.fixtures.RiderSpec
import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.testing.fixtures.SceneSpec
import net.belllap.vision.core.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlitScanCrossingDetectorTest {
    /** The synthetic scene's finish line: vertical at LINE_X, from the road top to the bottom of the frame. */
    private val lineX = SceneRenderer.LINE_X.toFloat() / SceneSpec.WIDTH
    private val line = FinishLine(
        NormPoint(lineX, SceneRenderer.ROAD_TOP.toFloat() / SceneSpec.HEIGHT),
        NormPoint(lineX, 1f),
    )

    private fun live(frames: List<Frame>, config: CrossingConfig = CrossingConfig()): List<DetectedCrossing> {
        val d = SlitScanCrossingDetector(line, config)
        return frames.flatMap(d::accept)
    }

    private fun scene(vararg crossFrames: Int, count: Int = 150) = SceneRenderer.render(
        SceneSpec(seed = 5, frameCount = count, banner = false, riders = crossFrames.map { RiderSpec(it, 1) }),
    )

    private fun frameOf(c: DetectedCrossing) = Math.round(c.ptsNs * SceneSpec.FPS / NS_PER_S).toInt()

    @Test
    fun singlesAndBunchesAreCountedAndTimedWithinTwoFrames() {
        val fixture = Fixtures.crossings()
        val found = live(fixture.frames!!)
        assertEquals(fixture.labels.crossings.size, found.size)
        fixture.labels.crossings.zip(found).forEach { (label, c) ->
            assertTrue("label ${label.frame} found ${frameOf(c)}", abs(frameOf(c) - label.frame) <= 2)
            assertTrue(c.confidence in 0f..1f)
        }
    }

    @Test
    fun anEmptyRoadAndTheWavingFlagGiveNoDetections() {
        assertEquals(0, live(Fixtures.flag().frames!!).size)
        assertEquals(0, live(Fixtures.banner().frames!!).size)
    }

    @Test
    fun aRiderAlreadyInViewAtTheStartDoesNotLeaveAGhostOnset() {
        val found = live(scene(-2, 60))
        assertEquals(listOf(60), found.map(::frameOf))
    }

    @Test
    fun aCrossingDuringWarmUpIsMissedLiveButRecoveredByTheRefiner() {
        val frames = scene(5, 60)
        assertEquals(listOf(60), live(frames).map(::frameOf))
        val refined = CrossingRefiner.refine(frames, line)
        assertEquals(listOf(5, 60), refined.map(::frameOf))
    }

    @Test
    fun theWheelHeightBandLimitsWhereRidersCount() {
        val frames = scene(40, count = 80) // rider 0 rides lane 0: rows 64..75, the top fifth of the line
        assertEquals(1, live(frames).size)
        assertEquals(0, live(frames, CrossingConfig(band = 0.5f..1f)).size)
    }

    @Test
    fun theRefinerMatchesTheLivePassOnTheFixture() {
        val fixture = Fixtures.crossings()
        val refined = CrossingRefiner.refine(fixture.frames!!, line)
        assertEquals(fixture.labels.crossings.map { it.frame }, refined.map(::frameOf))
    }

    private fun strip(runStart: Int?, runEnd: Int = 0): IntArray =
        IntArray(SAMPLES) { if (runStart != null && it in runStart..runEnd) HIGH else LOW }

    private fun track(vararg strips: IntArray): List<Int> {
        val tracker = OnsetTracker(CrossingConfig(warmupFrames = 0), SAMPLES)
        val bg = strip(null)
        return strips.flatMapIndexed { i, s ->
            tracker.process(i, i * FRAME_NS, s, bg).map { i }
        }
    }

    @Test
    fun aOneFrameDropoutOfTheSameRunIsFlickerNotANewRider() {
        val on = strip(10, 20)
        assertEquals(listOf(0), track(on, on, strip(null), on, on))
    }

    @Test
    fun aLongGapOrAnotherPlaceIsANewRider() {
        val on = strip(10, 20)
        val off = strip(null)
        assertEquals(listOf(0, 7), track(on, off, off, off, off, off, off, on))
        assertEquals(listOf(0, 3), track(on, on, strip(null), strip(40, 50)))
    }

    @Test
    fun smallGapsInsideARunAreBridgedAndTinyRunsAreIgnored() {
        val gappy = IntArray(SAMPLES) { if (it in 10..14 || it in 17..21) HIGH else LOW }
        assertEquals(listOf(0), track(gappy, gappy))
        assertEquals(emptyList<Int>(), track(strip(10, 10), strip(10, 10)))
    }

    private companion object {
        const val NS_PER_S = 1e9
        const val FRAME_NS = 33_333_333L
        const val SAMPLES = 60
        const val LOW = 100
        const val HIGH = 200
    }
}
