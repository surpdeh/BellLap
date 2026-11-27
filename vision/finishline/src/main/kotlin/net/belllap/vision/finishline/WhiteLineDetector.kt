package net.belllap.vision.finishline

import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.vision.core.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** A suggested finish line and how much to trust it ([confidence] in 0..1). */
data class LineSuggestion(val line: FinishLine, val confidence: Float)

/**
 * Finds the best roughly vertical white line in a frame (F12).
 *
 * Pipeline: box-downsample, white mask, then a Hough vote restricted to +-[MAX_TILT_DEG] of vertical, then the
 * longest unbroken run of mask pixels on the winning line, refined by a least-squares fit. Pure Kotlin on the
 * luma plane, so it behaves the same in JVM tests, in Replay and on the phone. It deliberately skips Canny: the
 * white mask already is the edge-free evidence, and a binary mask has nothing for Canny to add.
 */
object WhiteLineDetector {
    const val MAX_TILT_DEG = 20
    const val MIN_CONFIDENCE = 0.25f
    private const val MAX_WORK_WIDTH = 320
    private const val MIN_WHITE = 180
    private const val RELATIVE_WHITE = 0.85
    private const val BRIGHT_PERCENTILE = 0.995
    private const val MAX_MASK_FRACTION = 0.3
    private const val MIN_LENGTH = 0.15
    private const val FULL_SCORE_LENGTH = 0.5
    private const val STRIP_HALF_WIDTH = 2.0
    private const val MAX_GAP_FRACTION = 0.015
    private const val MIN_GAP_ROWS = 2
    private const val MID_GREY = 128.0
    private const val LEVELS = 256
    private const val BYTE_MASK = 0xFF
    private const val MIN_MASK_PIXELS = 8
    private const val BIN_SMOOTH = 1
    private const val HALF = 0.5
    private const val DEGREES_PER_HALF_TURN = 180

    fun detect(frame: Frame): LineSuggestion? {
        val img = downsample(frame)
        val mask = whiteMask(img) ?: return null
        val peak = houghPeak(mask, img)
        val strip = stripPixels(mask, img.w, peak)
        return fitRun(strip, img)
    }

    private class Gray(val w: Int, val h: Int, val px: IntArray)

    private class Peak(val tan: Double, val d: Double)

    private fun downsample(f: Frame): Gray {
        val s = max(1, ceil(f.width / MAX_WORK_WIDTH.toDouble()).toInt())
        val w = f.width / s
        val h = f.height / s
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until s) for (dx in 0 until s) sum += f.lumaAt(x * s + dx, y * s + dy)
                out[y * w + x] = sum / (s * s)
            }
        }
        return Gray(w, h, out)
    }

    /** Indices of white pixels, or null when the frame has too few or is washed out. */
    private fun whiteMask(img: Gray): IntArray? {
        val hist = IntArray(LEVELS)
        img.px.forEach { hist[it and BYTE_MASK]++ }
        var acc = 0
        var bright = 0
        val target = (img.px.size * BRIGHT_PERCENTILE).toInt()
        while (bright < LEVELS - 1 && acc + hist[bright] <= target) acc += hist[bright++]
        val threshold = max(MIN_WHITE, (bright * RELATIVE_WHITE).toInt())
        val idx = img.px.indices.filter { img.px[it] >= threshold }
        val ok = idx.size >= MIN_MASK_PIXELS && idx.size <= img.px.size * MAX_MASK_FRACTION
        return if (ok) idx.toIntArray() else null
    }

    private fun houghPeak(mask: IntArray, img: Gray): Peak {
        val tans = DoubleArray(2 * MAX_TILT_DEG + 1) { tan((it - MAX_TILT_DEG) * PI / DEGREES_PER_HALF_TURN) }
        val pad = ceil(img.h * tans.last()).toInt() + 2
        val bins = img.w + 2 * pad
        val votes = IntArray(tans.size * bins)
        for (i in mask) {
            val x = i % img.w + HALF
            val y = i / img.w + HALF
            for (a in tans.indices) votes[a * bins + (x - y * tans[a]).roundToInt() + pad]++
        }
        var best = -1
        var bestA = 0
        var bestB = 0
        for (a in tans.indices) {
            for (b in BIN_SMOOTH until bins - BIN_SMOOTH) {
                val v = (b - BIN_SMOOTH..b + BIN_SMOOTH).sumOf { votes[a * bins + it] }
                if (v > best) {
                    best = v
                    bestA = a
                    bestB = b
                }
            }
        }
        return Peak(tans[bestA], (bestB - pad).toDouble())
    }

    private fun stripPixels(mask: IntArray, w: Int, p: Peak): List<IntArray> =
        mask.filter { abs((it % w + HALF) - (it / w + HALF) * p.tan - p.d) <= STRIP_HALF_WIDTH }
            .map { intArrayOf(it % w, it / w) }

    private fun fitRun(strip: List<IntArray>, img: Gray): LineSuggestion? {
        if (strip.isEmpty()) return null
        val rows = strip.groupBy { it[1] }.toSortedMap()
        val maxGap = max(MIN_GAP_ROWS, (img.h * MAX_GAP_FRACTION).toInt())
        val run = longestRun(rows.keys.toList(), maxGap)
        val span = run.last() - run.first() + 1
        val length = span.toDouble() / img.h
        return if (length < MIN_LENGTH) null else suggest(run.flatMap { rows.getValue(it) }, run, span, length, img)
    }

    private fun suggest(pts: List<IntArray>, run: List<Int>, span: Int, length: Double, img: Gray): LineSuggestion? {
        val (a, b) = leastSquares(pts)
        val tilt = abs(b).coerceAtMost(tan(MAX_TILT_DEG * PI / DEGREES_PER_HALF_TURN))
        val slope = if (b < 0) -tilt else tilt
        val meanY = pts.sumOf { it[1] + HALF } / pts.size
        val white = pts.map { img.px[it[1] * img.w + it[0]] }.average()
        val fill = run.size.toDouble() / span
        val whiteness = ((white - MID_GREY) / (LEVELS - 1 - MID_GREY)).coerceIn(0.0, 1.0)
        val confidence = (min(1.0, length / FULL_SCORE_LENGTH) * fill * whiteness).toFloat()

        fun pt(y: Double) = NormPoint.clampOf(((a + slope * (y - meanY)) / img.w).toFloat(), (y / img.h).toFloat())
        return if (confidence < MIN_CONFIDENCE) {
            null
        } else {
            LineSuggestion(FinishLine(pt(run.first().toDouble()), pt(run.last() + 1.0)), confidence)
        }
    }

    /** The longest (by row span) run of sorted row numbers whose neighbours are at most [maxGap] apart. */
    private fun longestRun(rows: List<Int>, maxGap: Int): List<Int> {
        var bestStart = 0
        var bestEnd = 0
        var start = 0
        for (i in 1..rows.size) {
            if (i == rows.size || rows[i] - rows[i - 1] > maxGap) {
                if (rows[i - 1] - rows[start] > rows[bestEnd] - rows[bestStart]) {
                    bestStart = start
                    bestEnd = i - 1
                }
                start = i
            }
        }
        return rows.subList(bestStart, bestEnd + 1)
    }

    /** Least squares of x on y over pixel centres; returns (mean x, slope dx/dy). */
    private fun leastSquares(pts: List<IntArray>): Pair<Double, Double> {
        val my = pts.sumOf { it[1] + HALF } / pts.size
        val mx = pts.sumOf { it[0] + HALF } / pts.size
        val syy = pts.sumOf { (it[1] + HALF - my) * (it[1] + HALF - my) }
        val sxy = pts.sumOf { (it[0] + HALF - mx) * (it[1] + HALF - my) }
        return mx to if (syy > 0) sxy / syy else 0.0
    }
}
