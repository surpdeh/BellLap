package net.belllap.vision.finishline

import net.belllap.core.model.FinishLine
import net.belllap.vision.core.Frame
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Samples luma along the finish line into a 1-D strip (one column of the slit-scan image, F18).
 *
 * Sample `i` sits at `(i + 0.5) / samples` along the line (normalised sensor coordinates through
 * [FinishLine.toPixels]) and averages three pixels across the line, at -1, 0 and +1 px perpendicular, to cut noise.
 */
class StripSampler(line: FinishLine, private val frameW: Int, private val frameH: Int, val samples: Int) {
    private val pixels = IntArray(samples * TAPS)

    init {
        require(samples > 0 && frameW > 0 && frameH > 0) { "bad sampler size" }
        val (a, b) = line.toPixels(frameW, frameH)
        val dx = b.first - a.first
        val dy = b.second - a.second
        val length = hypot(dx, dy).coerceAtLeast(MIN_LENGTH_PX)
        val nx = -dy / length
        val ny = dx / length
        for (i in 0 until samples) {
            val t = (i + HALF) / samples
            val cx = a.first + dx * t
            val cy = a.second + dy * t
            for (k in 0 until TAPS) {
                val off = (k - TAPS / 2).toFloat()
                val x = floor(cx + nx * off).toInt().coerceIn(0, frameW - 1)
                val y = floor(cy + ny * off).toInt().coerceIn(0, frameH - 1)
                pixels[i * TAPS + k] = y * frameW + x
            }
        }
    }

    /** The strip of [frame], samples running from the line's start to its end. */
    fun sample(frame: Frame): IntArray {
        require(frame.width == frameW && frame.height == frameH) { "frame size changed" }
        return IntArray(samples) { i ->
            var sum = 0
            for (k in 0 until TAPS) {
                val p = pixels[i * TAPS + k]
                sum += frame.lumaAt(p % frameW, p / frameW)
            }
            (sum.toFloat() / TAPS).roundToInt()
        }
    }

    companion object {
        private const val TAPS = 3
        private const val HALF = 0.5f
        private const val MIN_LENGTH_PX = 1f
        private const val MIN_SAMPLES = 16
        private const val MAX_SAMPLES = 512

        /** About one sample per pixel of line length, within sane bounds. */
        fun defaultSamples(line: FinishLine, frameW: Int, frameH: Int): Int {
            val (a, b) = line.toPixels(frameW, frameH)
            return hypot(b.first - a.first, b.second - a.second).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        }
    }
}
