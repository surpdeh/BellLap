package net.belllap.bugreport

import net.belllap.core.model.Sidecar

/** A span of a clip, in microseconds from its first frame. */
data class TrimWindow(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs

    companion object {
        const val HALF_WINDOW_US = 5_000_000L

        /** [centerUs] plus or minus 5 s, clamped to a clip of [clipDurationUs]. A centre outside the clip snaps in. */
        fun around(centerUs: Long, clipDurationUs: Long): TrimWindow {
            val center = centerUs.coerceIn(0, clipDurationUs)
            return TrimWindow(
                (center - HALF_WINDOW_US).coerceAtLeast(0),
                (center + HALF_WINDOW_US).coerceAtMost(clipDurationUs),
            )
        }
    }
}

/** A moment in a captured video worth reporting on, as microseconds from the first frame. */
data class TrimCandidate(val label: String, val centerUs: Long)

/**
 * Crossings and flag intervals from the sidecar. Sidecar PTS are camera-clock values, so each is made relative to
 * [Sidecar.firstFramePtsUs], which is the same mapping review uses to seek.
 */
fun trimCandidates(sidecar: Sidecar): List<TrimCandidate> {
    val origin = sidecar.firstFramePtsUs
    val crossings = sidecar.crossings.mapIndexed { i, c -> TrimCandidate("Crossing ${i + 1}", c.ptsUs - origin) }
    val flags = sidecar.flagIntervals.flatMapIndexed { i, f ->
        listOf(
            TrimCandidate("Flag ${i + 1} seen", f.startPtsUs - origin),
            TrimCandidate("Flag ${i + 1} gone", f.endPtsUs - origin),
        )
    }
    return (crossings + flags).filter { it.centerUs >= 0 }.sortedBy { it.centerUs }
}
