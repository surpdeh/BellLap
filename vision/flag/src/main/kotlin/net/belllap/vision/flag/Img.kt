package net.belllap.vision.flag

import net.belllap.vision.core.Frame

/** A downscaled luma image. */
internal class Img(val px: IntArray, val w: Int, val h: Int) {
    /** Summed-area table, so any box sum is four reads. */
    val integral: IntArray = IntArray((w + 1) * (h + 1)).also { out ->
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += px[y * w + x]
                out[(y + 1) * (w + 1) + x + 1] = out[y * (w + 1) + x + 1] + row
            }
        }
    }

    private fun boxSum(x0: Int, y0: Int, x1: Int, y1: Int): Int =
        integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] +
            integral[y0 * (w + 1) + x0]

    private fun mean(x0: Int, y0: Int, x1: Int, y1: Int) = boxSum(x0, y0, x1, y1) / ((x1 - x0) * (y1 - y0))

    /** True if any pixel of the tile is the centre of an alternating X-corner at one of [radii]. */
    fun hasCorner(x0: Int, y0: Int, tile: Int, radii: List<Int>, contrast: Int): Boolean {
        for (r in radii) {
            for (cy in maxOf(y0, r)..minOf(y0 + tile - 1, h - r)) {
                for (cx in maxOf(x0, r)..minOf(x0 + tile - 1, w - r)) {
                    if (isCorner(cx, cy, r, contrast)) return true
                }
            }
        }
        return false
    }

    /** Diagonal quadrants share a polarity and the two diagonals differ by at least [contrast]. */
    private fun isCorner(cx: Int, cy: Int, r: Int, contrast: Int): Boolean {
        val a = mean(cx - r, cy - r, cx, cy)
        val b = mean(cx, cy - r, cx + r, cy)
        val c = mean(cx - r, cy, cx, cy + r)
        val d = mean(cx, cy, cx + r, cy + r)
        val gap = Math.abs((a + d) - (b + c)) / 2
        return gap >= contrast && Math.abs(a - d) <= gap / 2 && Math.abs(b - c) <= gap / 2
    }

    /** Both clearly dark and clearly light pixels, each a real share of the tile. */
    fun isBimodal(x0: Int, y0: Int, tile: Int, minRange: Int, share: Double): Boolean {
        var lo = 255
        var hi = 0
        forTile(x0, y0, tile) { v ->
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        if (hi - lo < minRange) return false
        val margin = (hi - lo) / 4
        val mid = (lo + hi) / 2
        var dark = 0
        var light = 0
        forTile(x0, y0, tile) { v ->
            if (v < mid - margin) dark++
            if (v > mid + margin) light++
        }
        val need = share * tile * tile
        return dark >= need && light >= need
    }

    /** Share of the tile's pixels that moved by more than [delta] since [last]. */
    fun movedShare(last: IntArray, x0: Int, y0: Int, tile: Int, delta: Int): Double {
        var changed = 0
        for (y in y0 until minOf(y0 + tile, h)) {
            for (x in x0 until minOf(x0 + tile, w)) {
                if (Math.abs(px[y * w + x] - last[y * w + x]) > delta) changed++
            }
        }
        return changed.toDouble() / (tile * tile)
    }

    private inline fun forTile(x0: Int, y0: Int, tile: Int, f: (Int) -> Unit) {
        for (y in y0 until minOf(y0 + tile, h)) for (x in x0 until minOf(x0 + tile, w)) f(px[y * w + x])
    }

    companion object {
        /** Integer box-average so the image is at most [maxWidth] wide. */
        fun of(f: Frame, maxWidth: Int): Img {
            val k = maxOf(1, f.width / maxWidth)
            val w = f.width / k
            val h = f.height / k
            val px = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0
                    for (dy in 0 until k) for (dx in 0 until k) sum += f.lumaAt(x * k + dx, y * k + dy)
                    px[y * w + x] = sum / (k * k)
                }
            }
            return Img(px, w, h)
        }
    }
}
