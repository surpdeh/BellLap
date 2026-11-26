package net.belllap.core.log

/**
 * Decides which detector scores are worth logging: the first per detector, then one every
 * [minIntervalNs] of PTS, or earlier when the score moves by at least [minDelta].
 */
class ScoreDecimator(
    private val minIntervalNs: Long = 500_000_000L,
    private val minDelta: Double = 0.2,
) {
    private class Last(val ptsNs: Long, val score: Double)

    private val last = HashMap<String, Last>()

    @Synchronized
    fun shouldLog(detector: String, score: Double, ptsNs: Long): Boolean {
        val prev = last[detector]
        val log = prev == null ||
            ptsNs < prev.ptsNs ||
            ptsNs - prev.ptsNs >= minIntervalNs ||
            kotlin.math.abs(score - prev.score) >= minDelta
        if (log) last[detector] = Last(ptsNs, score)
        return log
    }
}
