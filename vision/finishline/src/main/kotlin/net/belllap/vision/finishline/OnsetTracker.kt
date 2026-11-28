package net.belllap.vision.finishline

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A finish-line crossing: [ptsNs] is the PTS of the first frame in which the rider covers the line. */
data class DetectedCrossing(val ptsNs: Long, val frameIndex: Int, val confidence: Float, val centerFraction: Float)

/**
 * Tuning for the slit-scan detector.
 *
 * [threshold] is the luma difference from the background that counts as foreground. [band] is the part of the line,
 * as a 0..1 fraction from its start, where a wheel can be (the wheel-height band); the default is the whole line.
 * [minRunFraction] is the smallest foreground run, as a fraction of the strip. [bridgeSamples] closes small gaps
 * inside a run. [dilateSamples] widens a run when matching it to the previous frame. [minInterOnsetMs] stops a
 * rider that flickers (spokes, frame) from re-triggering. [warmupFrames] frames at the start report nothing, so a
 * rider already in view cannot leave a ghost. [medianWindow] is the live background window in frames.
 */
data class CrossingConfig(
    val threshold: Int = DEFAULT_THRESHOLD,
    val band: ClosedFloatingPointRange<Float> = 0f..1f,
    val minRunFraction: Float = DEFAULT_MIN_RUN,
    val bridgeSamples: Int = DEFAULT_BRIDGE,
    val dilateSamples: Int = DEFAULT_DILATE,
    val minInterOnsetMs: Long = DEFAULT_INTER_ONSET_MS,
    val warmupFrames: Int = DEFAULT_WARMUP,
    val medianWindow: Int = RunningMedianBackground.DEFAULT_WINDOW,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 30
        const val DEFAULT_MIN_RUN = 0.05f
        const val DEFAULT_BRIDGE = 2
        const val DEFAULT_DILATE = 2
        const val DEFAULT_INTER_ONSET_MS = 80L
        const val DEFAULT_WARMUP = 16
        const val REFINE_THRESHOLD = 24

        /** The refinement pass: a little more sensitive, and no warm-up because the background is centred. */
        val REFINE = CrossingConfig(threshold = REFINE_THRESHOLD, warmupFrames = 0)
    }
}

/**
 * The domain state machine behind both passes. Per frame it marks the strip samples that differ from the background
 * inside the wheel-height band, bridges small gaps and extracts runs. A run is a new rider when it does not overlap
 * a run seen in the previous frame, nor one seen within the minimum inter-onset gap.
 */
class OnsetTracker(private val config: CrossingConfig, private val samples: Int) {
    private class Run(var start: Int, var end: Int, var lastSeenNs: Long = 0L, var strength: Float = 0f)

    private val bandLo = ceil(config.band.start * (samples - 1)).toInt().coerceAtLeast(0)
    private val bandHi = floor(config.band.endInclusive * (samples - 1)).toInt().coerceAtMost(samples - 1)
    private val minRun = max(MIN_RUN_SAMPLES, Math.round(config.minRunFraction * samples))
    private val minGapNs = config.minInterOnsetMs * NS_PER_MS
    private val tracked = ArrayList<Run>()
    private var seen = 0
    private var previousPtsNs = Long.MIN_VALUE

    /** Feeds one frame's [strip] against its [background]; returns the riders whose onset is this frame. */
    fun process(frameIndex: Int, ptsNs: Long, strip: IntArray, background: IntArray): List<DetectedCrossing> {
        val reporting = seen++ >= config.warmupFrames
        val out = ArrayList<DetectedCrossing>()
        val wasSeen = tracked.filter { it.lastSeenNs == previousPtsNs || ptsNs - it.lastSeenNs < minGapNs }
        for (run in runs(strip, background)) {
            val match = wasSeen.firstOrNull { overlaps(it, run) }
            if (match != null) {
                match.start = run.start
                match.end = run.end
                match.lastSeenNs = ptsNs
                continue
            }
            run.lastSeenNs = ptsNs
            tracked += run
            if (reporting) out += crossing(frameIndex, ptsNs, run)
        }
        tracked.removeAll { it.lastSeenNs != ptsNs && ptsNs - it.lastSeenNs >= minGapNs }
        previousPtsNs = ptsNs
        return out
    }

    private fun overlaps(a: Run, b: Run) =
        a.start <= b.end + config.dilateSamples && b.start <= a.end + config.dilateSamples

    private fun runs(strip: IntArray, background: IntArray): List<Run> {
        val raw = ArrayList<Run>()
        var start = -1
        var sum = 0
        for (i in bandLo..bandHi + 1) {
            val diff = if (i <= bandHi) abs(strip[i] - background[i]) else 0
            if (diff > config.threshold) {
                if (start < 0) start = i
                sum += diff
            } else if (start >= 0) {
                raw += Run(start, i - 1, strength = sum.toFloat() / (i - start))
                start = -1
                sum = 0
            }
        }
        return merge(raw).filter { it.end - it.start + 1 >= minRun }
    }

    private fun merge(raw: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r.start - last.end - 1 <= config.bridgeSamples) {
                val wl = (last.end - last.start + 1).toFloat()
                val wr = (r.end - r.start + 1).toFloat()
                last.strength = (last.strength * wl + r.strength * wr) / (wl + wr)
                last.end = r.end
            } else {
                out += Run(r.start, r.end, strength = r.strength)
            }
        }
        return out
    }

    private fun crossing(frameIndex: Int, ptsNs: Long, run: Run): DetectedCrossing {
        val size = (run.end - run.start + 1).toFloat() / (minRun * SIZE_FULL_FACTOR)
        val contrast = run.strength / FULL_CONTRAST
        val center = (run.start + run.end) / 2f / max(1, samples - 1)
        return DetectedCrossing(ptsNs, frameIndex, min(1f, size) * min(1f, contrast), center)
    }

    private companion object {
        const val MIN_RUN_SAMPLES = 2
        const val NS_PER_MS = 1_000_000L
        const val SIZE_FULL_FACTOR = 3
        const val FULL_CONTRAST = 100f
    }
}
