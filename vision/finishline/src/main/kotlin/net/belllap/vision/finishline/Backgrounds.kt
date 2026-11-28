package net.belllap.vision.finishline

/** Supplies the empty-road strip to compare each frame against. Called once per frame, in frame order. */
interface BackgroundModel {
    /** The background for frame [index], whose strip is [strip]. */
    fun backgroundFor(index: Int, strip: IntArray): IntArray
}

private fun medianOf(window: List<IntArray>, samples: Int): IntArray {
    val column = IntArray(window.size)
    return IntArray(samples) { s ->
        for (i in window.indices) column[i] = window[i][s]
        column.sort()
        column[column.size / 2]
    }
}

/**
 * Causal background for the live pass: the per-sample median of the previous [window] strips. The first frame is
 * its own background, so it never reports a rider. A rider that stays on the line for fewer than half the window
 * does not move the median.
 */
class RunningMedianBackground(private val window: Int = DEFAULT_WINDOW) : BackgroundModel {
    private val history = ArrayDeque<IntArray>()

    init {
        require(window >= 1) { "window must be positive" }
    }

    override fun backgroundFor(index: Int, strip: IntArray): IntArray {
        val bg = if (history.isEmpty()) strip else medianOf(history, strip.size)
        history.addLast(strip)
        if (history.size > window) history.removeFirst()
        return bg
    }

    companion object {
        const val DEFAULT_WINDOW = 31
    }
}

/**
 * Non-causal background for the refinement pass: the median of the stored strips `index - halfWindow..index +
 * halfWindow`. It sees the road both before and after a rider, so there is no warm-up and no ghost.
 */
class CenteredMedianBackground(private val strips: List<IntArray>, private val halfWindow: Int = DEFAULT_HALF) :
    BackgroundModel {
    override fun backgroundFor(index: Int, strip: IntArray): IntArray {
        val from = (index - halfWindow).coerceAtLeast(0)
        val to = (index + halfWindow).coerceAtMost(strips.lastIndex)
        return medianOf(strips.subList(from, to + 1), strip.size)
    }

    companion object {
        const val DEFAULT_HALF = 15
    }
}
