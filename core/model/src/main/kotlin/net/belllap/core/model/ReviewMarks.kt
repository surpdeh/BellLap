package net.belllap.core.model

/** Finisher-mark edits for review (F19). Each returns a new [Sidecar]; added or moved marks are MANUAL, confidence 1. */

fun Sidecar.withCrossingAdded(ptsUs: Long, frameIndex: Int): Sidecar =
    copy(crossings = (crossings + manual(ptsUs, frameIndex)).sortedBy { it.ptsUs })

fun Sidecar.withCrossingMoved(index: Int, ptsUs: Long, frameIndex: Int): Sidecar {
    if (index !in crossings.indices) return this
    return copy(crossings = crossings.filterIndexed { i, _ -> i != index }.plus(manual(ptsUs, frameIndex)).sortedBy { it.ptsUs })
}

fun Sidecar.withCrossingRemoved(index: Int): Sidecar {
    if (index !in crossings.indices) return this
    return copy(crossings = crossings.filterIndexed { i, _ -> i != index })
}

/**
 * Index of the last mark on a frame before [frameIndex], or null. Marks compare by their nearest frame in [clock],
 * not by raw PTS, so a mark whose PTS sits a little off the frame's nominal PTS is not found again from its own frame.
 */
fun Sidecar.previousCrossing(clock: FrameClock, frameIndex: Int): Int? =
    crossings.indices.lastOrNull { clock.indexOfPts(crossings[it].ptsUs) < frameIndex }

/** Index of the first mark on a frame after [frameIndex], or null. */
fun Sidecar.nextCrossing(clock: FrameClock, frameIndex: Int): Int? =
    crossings.indices.firstOrNull { clock.indexOfPts(crossings[it].ptsUs) > frameIndex }

/** Index of a mark on [frameIndex], or null. */
fun Sidecar.markAt(clock: FrameClock, frameIndex: Int): Int? =
    crossings.indices.firstOrNull { clock.indexOfPts(crossings[it].ptsUs) == frameIndex }

private fun manual(ptsUs: Long, frameIndex: Int) = SidecarCrossing(ptsUs, frameIndex, 1f, CrossingSource.MANUAL)
