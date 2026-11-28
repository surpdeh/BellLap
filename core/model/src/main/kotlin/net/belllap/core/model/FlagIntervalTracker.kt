package net.belllap.core.model

/**
 * Collects the intervals during which the flag was visible, by frame PTS (microseconds), while one acquisition runs.
 * Call [setVisible] on every change and [finish] once at the end; repeated calls with the same value are ignored.
 */
class FlagIntervalTracker(startPtsUs: Long, visible: Boolean) {
    private val done = mutableListOf<FlagInterval>()
    private var openSince: Long? = if (visible) startPtsUs else null

    fun setVisible(visible: Boolean, ptsUs: Long) {
        val since = openSince
        if (visible && since == null) {
            openSince = ptsUs
        } else if (!visible && since != null) {
            done += FlagInterval(since, maxOf(since, ptsUs))
            openSince = null
        }
    }

    /** Closes an interval still open at [endPtsUs] and returns all of them in order. */
    fun finish(endPtsUs: Long): List<FlagInterval> {
        setVisible(false, endPtsUs)
        return done.toList()
    }
}
