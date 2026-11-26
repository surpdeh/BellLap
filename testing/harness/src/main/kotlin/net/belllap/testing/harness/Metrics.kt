package net.belllap.testing.harness

import kotlin.math.abs

/** Pure metric functions over detector output and labels. All times are PTS in nanoseconds. */
object Metrics {
    class Match(val tp: Int, val fp: Int, val fn: Int, val errorsNs: List<Long>)

    /** Greedy one-to-one matching: each label takes the nearest unused detection within [toleranceNs]. */
    fun match(labels: List<Long>, detections: List<Long>, toleranceNs: Long): Match {
        val used = BooleanArray(detections.size)
        val errors = ArrayList<Long>()
        for (label in labels) {
            val best = detections.indices.filter { !used[it] }.minByOrNull { abs(detections[it] - label) }
            if (best != null && abs(detections[best] - label) <= toleranceNs) {
                used[best] = true
                errors += detections[best] - label
            }
        }
        val tp = errors.size
        return Match(tp, detections.size - tp, labels.size - tp, errors)
    }

    fun recall(m: Match) = if (m.tp + m.fn == 0) 1.0 else m.tp.toDouble() / (m.tp + m.fn)

    fun precision(m: Match) = if (m.tp + m.fp == 0) 1.0 else m.tp.toDouble() / (m.tp + m.fp)

    fun meanAbsError(m: Match) = if (m.errorsNs.isEmpty()) 0.0 else m.errorsNs.map { abs(it).toDouble() }.average()

    fun falseAlarmsPerHour(falsePositives: Int, durationS: Double) = falsePositives * SECONDS_PER_HOUR / durationS

    /** Intersection over union of two sets of intervals, 1.0 when both are empty. */
    fun intervalIou(truth: List<LongRange>, found: List<LongRange>): Double {
        val inter = truth.sumOf { t -> found.sumOf { overlap(t, it) } }
        val union = truth.sumOf { it.last - it.first } + found.sumOf { it.last - it.first } - inter
        return if (union <= 0) 1.0 else inter.toDouble() / union
    }

    private fun overlap(a: LongRange, b: LongRange) = maxOf(0L, minOf(a.last, b.last) - maxOf(a.first, b.first))

    private const val SECONDS_PER_HOUR = 3600.0
}
