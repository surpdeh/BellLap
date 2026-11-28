package net.belllap.core.replay

import net.belllap.audio.source.PcmChunk
import net.belllap.vision.core.Frame

/** Bell detector seam. Returns true when a bell onset is found in [chunk]. Real implementation: F14. */
fun interface BellDetector {
    fun accept(chunk: PcmChunk): Boolean
}

/** Flag detector seam. Returns whether the chequered flag is visible in [frame]. Real implementation: F16. */
fun interface FlagDetector {
    fun accept(frame: Frame): Boolean
}

/** Finish-crossing detector seam. Returns true when a rider crosses the line in [frame]. Real implementation: F18 (`SlitScanCrossingDetector`). */
fun interface CrossingDetector {
    fun accept(frame: Frame): Boolean
}

/** The three detectors a session needs. */
class Detectors(val bell: BellDetector, val flag: FlagDetector, val crossing: CrossingDetector) {
    companion object {
        /** Detects nothing. Used when replaying an arbitrary file that has no labels. */
        val NONE = Detectors({ false }, { false }, { false })
    }
}

/**
 * Stand-in detectors driven by ground-truth times (PTS, ns), so the wiring can be verified before the real
 * detectors land (F14, F16, F18). They look only at the PTS of what they are given, like a real detector would.
 */
object LabelDrivenDetectors {
    fun of(bellOnsetsNs: List<Long>, flagIntervals: List<LongRange>, crossingsNs: List<Long>): Detectors {
        val bells = bellOnsetsNs.sorted()
        val crossings = crossingsNs.sorted()
        var nextBell = 0
        var nextCrossing = 0
        return Detectors(
            bell = {
                var hit = false
                while (nextBell < bells.size && bells[nextBell] < it.ptsNs + it.durationNs) {
                    hit = true
                    nextBell++
                }
                hit
            },
            flag = { frame -> flagIntervals.any { frame.ptsNs in it } },
            crossing = { frame ->
                var hit = false
                while (nextCrossing < crossings.size && crossings[nextCrossing] <= frame.ptsNs) {
                    hit = true
                    nextCrossing++
                }
                hit
            },
        )
    }
}
