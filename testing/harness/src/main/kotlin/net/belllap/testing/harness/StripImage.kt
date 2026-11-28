package net.belllap.testing.harness

import net.belllap.vision.finishline.DetectedCrossing
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * The slit-scan strip as an image for the PR: columns are frames (time), rows are samples along the line. Red lines
 * are detected onsets; yellow ticks on the top and bottom edge are the labelled crossing frames.
 */
object StripImage {
    private const val COL_PX = 3
    private const val ROW_PX = 4
    private const val TICK_PX = 10
    private const val ONSET_PX = 2
    private val ONSET = Color(255, 40, 40)
    private val TRUTH = Color(255, 220, 0)

    fun render(strips: List<IntArray>, detected: List<DetectedCrossing>, truthFrames: List<Int>): BufferedImage {
        val rows = strips.first().size
        val img = BufferedImage(strips.size * COL_PX, rows * ROW_PX, BufferedImage.TYPE_INT_RGB)
        strips.forEachIndexed { x, strip ->
            strip.forEachIndexed { y, v ->
                val rgb = Color(v, v, v).rgb
                for (dx in 0 until COL_PX) for (dy in 0 until ROW_PX) img.setRGB(x * COL_PX + dx, y * ROW_PX + dy, rgb)
            }
        }
        for (c in detected) {
            for (dx in -ONSET_PX until 0) {
                for (y in 0 until img.height) img.setRGB((c.frameIndex * COL_PX + dx).coerceAtLeast(0), y, ONSET.rgb)
            }
        }
        for (f in truthFrames) {
            for (dx in 0 until COL_PX) {
                for (t in 0 until TICK_PX) {
                    img.setRGB(f * COL_PX + dx, t, TRUTH.rgb)
                    img.setRGB(f * COL_PX + dx, img.height - 1 - t, TRUTH.rgb)
                }
            }
        }
        return img
    }
}
