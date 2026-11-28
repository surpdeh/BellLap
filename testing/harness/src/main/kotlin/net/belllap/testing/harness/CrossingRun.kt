package net.belllap.testing.harness

import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.testing.fixtures.SceneSpec
import net.belllap.vision.core.Frame
import net.belllap.vision.finishline.CrossingRefiner
import net.belllap.vision.finishline.DetectedCrossing
import net.belllap.vision.finishline.SlitScanCrossingDetector

/** The F18 detector run over a frame list: the live pass (with its strips) and the refinement pass. */
class CrossingRun(val strips: List<IntArray>, val live: List<DetectedCrossing>, val refined: List<DetectedCrossing>) {
    companion object {
        private const val LINE_X = SceneRenderer.LINE_X.toFloat() / SceneSpec.WIDTH

        /** The synthetic scene's finish line: vertical at the scene's line column, road top to frame bottom. */
        val SYNTH_LINE = FinishLine(
            NormPoint(LINE_X, SceneRenderer.ROAD_TOP.toFloat() / SceneSpec.HEIGHT),
            NormPoint(LINE_X, 1f),
        )

        fun over(frames: List<Frame>, line: FinishLine = SYNTH_LINE): CrossingRun {
            val detector = SlitScanCrossingDetector(line)
            val strips = ArrayList<IntArray>()
            val live = frames.flatMap { f -> detector.accept(f).also { strips += checkNotNull(detector.lastStrip) } }
            return CrossingRun(strips, live, CrossingRefiner.refine(frames, line))
        }
    }
}
