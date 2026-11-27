package net.belllap.testing.harness

import net.belllap.vision.core.Frame
import net.belllap.vision.flag.ChequeredFlagDetector
import net.belllap.vision.flag.FlagConfig
import net.belllap.vision.flag.FlagResult

/** The F16 detector run over a frame list: per-frame results and the visible intervals they add up to. */
class FlagRun(val results: List<FlagResult>, val intervals: List<LongRange>) {
    val visible: List<Boolean> get() = results.map { it.visible }

    companion object {
        fun over(frames: List<Frame>, config: FlagConfig = FlagConfig()): FlagRun {
            val detector = ChequeredFlagDetector(config)
            val results = frames.map(detector::accept)
            val intervals = ArrayList<LongRange>()
            var start = -1L
            frames.forEachIndexed { i, f ->
                if (results[i].visible && start < 0) start = f.ptsNs
                if (!results[i].visible && start >= 0) {
                    intervals += start..frames[i - 1].ptsNs
                    start = -1
                }
            }
            if (start >= 0) intervals += start..frames.last().ptsNs
            return FlagRun(results, intervals)
        }
    }
}
