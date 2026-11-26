package net.belllap.testing.fixtures

import net.belllap.vision.core.Frame
import net.belllap.vision.core.LumaImages
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Human-readable evidence for PRs: a spectrogram of audio and a contact sheet of frames. */
object EvidenceImages {
    private const val WINDOW = 512
    private const val HOP = 256
    private const val DB_RANGE = 70.0
    private const val HEAT_STOPS = 255

    fun spectrogram(samples: ShortArray, sampleRate: Int): BufferedImage {
        val cols = (samples.size - WINDOW) / HOP
        val bins = WINDOW / 2
        val db = Array(cols) { c ->
            val re = DoubleArray(WINDOW) { samples[c * HOP + it] * (0.5 - 0.5 * cos(2 * PI * it / WINDOW)) }
            val im = DoubleArray(WINDOW)
            fft(re, im)
            DoubleArray(bins) { 20 * ln(sqrt(re[it] * re[it] + im[it] * im[it]) + 1.0) / ln(10.0) }
        }
        val max = db.maxOf { it.max() }
        val img = BufferedImage(cols, bins, BufferedImage.TYPE_INT_RGB)
        for (c in 0 until cols) for (b in 0 until bins) {
            val v = ((db[c][b] - (max - DB_RANGE)) / DB_RANGE).coerceIn(0.0, 1.0)
            img.setRGB(c, bins - 1 - b, heat(v))
        }
        check(sampleRate > 0)
        return img
    }

    /** Tiles the given frames in a grid. A red border marks a frame in [highlight] (for example a crossing frame). */
    fun contactSheet(
        frames: List<Frame>,
        indices: List<Int>,
        columns: Int,
        highlight: Set<Int> = emptySet(),
    ): BufferedImage {
        val f0 = frames.first()
        val pad = 2
        val rows = (indices.size + columns - 1) / columns
        val img = BufferedImage(columns * (f0.width + pad), rows * (f0.height + pad), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40, 40, 40)
        g.fillRect(0, 0, img.width, img.height)
        indices.forEachIndexed { i, idx ->
            val x = i % columns * (f0.width + pad)
            val y = i / columns * (f0.height + pad)
            g.drawImage(LumaImages.toImage(frames[idx]), x, y, null)
            if (idx in highlight) {
                g.color = Color.RED
                g.drawRect(x, y, f0.width - 1, f0.height - 1)
            }
        }
        g.dispose()
        return img
    }

    private fun heat(v: Double): Int {
        val k = (v * HEAT_STOPS).toInt()
        return Color(k, (k * k / HEAT_STOPS), if (k < HEAT_STOPS / 2) k * 2 else 255 - k).rgb
    }

    /** In-place radix-2 FFT; the length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k)
                    val wi = kotlin.math.sin(ang * k)
                    val a = i + k
                    val b = i + k + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }
}
