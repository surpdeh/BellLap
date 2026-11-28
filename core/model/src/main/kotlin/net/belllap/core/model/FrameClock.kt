package net.belllap.core.model

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Maps between frame indices, camera-clock PTS and the player's position for one acquisition (F19). The MP4 timeline
 * starts at its first frame, so a position is `ptsUs - firstFramePtsUs`. Pure, so exact stepping is unit tested.
 *
 * A seek goes to the middle of a frame's interval ([positionUs]), not its nominal start, so the real PTS jitter of a
 * variable-rate recording cannot land the seek on the previous frame. [indexAt] is the inverse of that.
 */
class FrameClock(val fps: Int, val firstFramePtsUs: Long, val lastIndex: Int = Int.MAX_VALUE) {
    init {
        require(fps > 0) { "fps must be positive" }
        require(lastIndex >= 0) { "lastIndex must not be negative" }
    }

    /** Player position that shows frame [index]. */
    fun positionUs(index: Int): Long = (2L * index + 1) * US_PER_S / (2L * fps)

    /** The frame shown at player position [positionUs], clamped to the clip. */
    fun indexAt(positionUs: Long): Int = (positionUs.coerceAtLeast(0) * fps / US_PER_S).coerceAtMost(lastIndex.toLong()).toInt()

    /** Player position for a camera-clock [ptsUs], never negative. */
    fun ptsToPosition(ptsUs: Long): Long = (ptsUs - firstFramePtsUs).coerceAtLeast(0)

    /** The frame nearest to a recorded [ptsUs] (a crossing, say), clamped to the clip. */
    fun indexOfPts(ptsUs: Long): Int =
        (ptsToPosition(ptsUs) * fps.toDouble() / US_PER_S).roundToInt().coerceAtMost(lastIndex)

    /** Nominal camera-clock PTS of frame [index]; what a mark added on that frame stores. */
    fun ptsOf(index: Int): Long = firstFramePtsUs + (index.toDouble() * US_PER_S / fps).roundToLong()

    /** Frame [by] steps from [index], clamped to `0..lastIndex`. */
    fun step(index: Int, by: Int): Int = (index.toLong() + by).coerceIn(0L, lastIndex.toLong()).toInt()

    companion object {
        private const val US_PER_S = 1_000_000L

        /** A clock for a clip of [durationUs]: its last frame is the last whole frame that fits. */
        fun forDuration(fps: Int, firstFramePtsUs: Long, durationUs: Long): FrameClock =
            FrameClock(fps, firstFramePtsUs, (durationUs.coerceAtLeast(0) * fps / US_PER_S - 1).coerceAtLeast(0).toInt())
    }
}
