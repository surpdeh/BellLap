package net.belllap.vision.flag

import net.belllap.vision.core.Frame

/**
 * Finds a waved chequered flag on luma frames. Pure Kotlin, PTS time base, one instance per session.
 *
 * Per frame, on a downscaled copy: tiles that hold polarity-alternating X-corners and a bimodal histogram are
 * "chequered"; tiles whose pixels changed recently are "moving". Chequered and moving tiles are grouped, and the
 * largest group (if big enough) is the candidate flag box. A static banner is chequered but never moving.
 * Hysteresis then turns the candidate into [FlagResult.visible].
 */
class ChequeredFlagDetector(private val config: FlagConfig = FlagConfig()) {
    private var prev: IntArray? = null
    private var prevW = 0
    private var prevH = 0
    private var lastMotionNs = LongArray(0)

    private var candidateSinceNs = NONE
    private var lastCandidateNs = NONE
    private var lostSinceNs = NONE
    private var visible = false

    fun accept(frame: Frame): FlagResult {
        val img = Img.of(frame, config.analysisWidth)
        val grid = Grid((img.w + config.tile - 1) / config.tile, (img.h + config.tile - 1) / config.tile)
        val last = prev?.takeIf { prevW == img.w && prevH == img.h }
        if (last == null) lastMotionNs = LongArray(grid.size) { Long.MIN_VALUE / 2 }
        val chequered = BooleanArray(grid.size)
        val moving = BooleanArray(grid.size)
        val region = region(img.w, img.h)
        for (idx in 0 until grid.size) {
            val x0 = idx % grid.tx * config.tile
            val y0 = idx / grid.tx * config.tile
            if (!region.holds(x0, y0, config.tile)) continue
            if (last != null && isMoving(img, last, x0, y0)) lastMotionNs[idx] = frame.ptsNs
            moving[idx] = frame.ptsNs - lastMotionNs[idx] <= config.motionHoldNs
            chequered[idx] = isChequered(img, x0, y0)
        }
        prev = img.px
        prevW = img.w
        prevH = img.h
        val scale = frame.width.toDouble() / img.w
        val textureBox = largest(chequered, grid, img, scale)
        val both = BooleanArray(grid.size) { chequered[it] && moving[it] }
        val flagBox = largest(both, grid, img, scale)
        update(flagBox != null, frame.ptsNs)
        return FlagResult(visible, flagBox, textureBox)
    }

    private fun isMoving(img: Img, last: IntArray, x0: Int, y0: Int): Boolean =
        img.movedShare(last, x0, y0, config.tile, config.motionDelta) >= config.motionShare

    private fun isChequered(img: Img, x0: Int, y0: Int): Boolean =
        img.hasCorner(x0, y0, config.tile, config.cornerRadii, config.cornerContrast) &&
            img.isBimodal(x0, y0, config.tile, config.cornerContrast, config.bimodalShare)

    private fun update(candidate: Boolean, ptsNs: Long) {
        if (candidate) {
            if (candidateSinceNs == NONE || ptsNs - lastCandidateNs > config.onGapNs) candidateSinceNs = ptsNs
            lastCandidateNs = ptsNs
            lostSinceNs = NONE
            if (!visible && ptsNs - candidateSinceNs >= config.onNs) visible = true
        } else if (visible) {
            if (lostSinceNs == NONE) lostSinceNs = ptsNs
            if (ptsNs - lostSinceNs >= config.offNs) {
                visible = false
                candidateSinceNs = NONE
            }
        } else if (candidateSinceNs != NONE && ptsNs - lastCandidateNs > config.onGapNs) {
            candidateSinceNs = NONE
        }
    }

    private class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun holds(x: Int, y: Int, size: Int) = x >= left && y >= top && x + size <= right && y + size <= bottom
    }

    private class Grid(val tx: Int, val ty: Int) {
        val size get() = tx * ty
    }

    private fun region(w: Int, h: Int): Region {
        val r = config.roi ?: return Region(0, 0, w, h)
        return Region((r.left * w).toInt(), (r.top * h).toInt(), (r.right * w).toInt(), (r.bottom * h).toInt())
    }

    /** Bounding box (in full-frame pixels) of the biggest group of set tiles, if it is big enough to be a flag. */
    private fun largest(set: BooleanArray, grid: Grid, img: Img, scale: Double): FlagBox? {
        val seen = BooleanArray(set.size)
        var best: List<Int> = emptyList()
        for (start in set.indices) {
            if (!set[start] || seen[start]) continue
            val group = group(start, set, seen, grid)
            if (group.size > best.size) best = group
        }
        val area = best.size * config.tile * config.tile
        if (best.size < config.minTiles || area < config.minAreaFraction * img.w * img.h) return null
        val x0 = best.minOf { it % grid.tx } * config.tile
        val y0 = best.minOf { it / grid.tx } * config.tile
        val x1 = (best.maxOf { it % grid.tx } + 1) * config.tile
        val y1 = (best.maxOf { it / grid.tx } + 1) * config.tile
        return FlagBox(
            (x0 * scale).toInt(),
            (y0 * scale).toInt(),
            ((x1 - x0) * scale).toInt(),
            ((y1 - y0) * scale).toInt(),
        )
    }

    /** Flood fill from [start], joining tiles up to [FlagConfig.joinTiles] apart. */
    private fun group(start: Int, set: BooleanArray, seen: BooleanArray, grid: Grid): List<Int> {
        val group = ArrayList<Int>()
        val stack = ArrayDeque<Int>().apply { add(start) }
        seen[start] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            group += i
            for (j in neighbours(i, grid)) {
                if (set[j] && !seen[j]) {
                    seen[j] = true
                    stack.add(j)
                }
            }
        }
        return group
    }

    private fun neighbours(i: Int, grid: Grid): List<Int> {
        val cx = i % grid.tx
        val cy = i / grid.tx
        val xs = maxOf(0, cx - config.joinTiles)..minOf(grid.tx - 1, cx + config.joinTiles)
        val ys = maxOf(0, cy - config.joinTiles)..minOf(grid.ty - 1, cy + config.joinTiles)
        return ys.flatMap { y -> xs.map { x -> y * grid.tx + x } }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
