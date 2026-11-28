package net.belllap.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.belllap.core.model.CrossingSource
import net.belllap.core.model.FrameClock
import net.belllap.core.model.Sidecar
import net.belllap.core.model.Vec2
import net.belllap.core.model.ZoomPan
import net.belllap.core.model.markAt
import net.belllap.core.model.nextCrossing
import net.belllap.core.model.previousCrossing
import net.belllap.core.model.withCrossingAdded
import net.belllap.core.model.withCrossingMoved
import net.belllap.core.model.withCrossingRemoved
import kotlin.math.roundToInt

/**
 * Review state for one acquisition (F19): the sidecar being edited, the current frame, the selected mark and the zoom.
 * Plain Kotlin apart from Compose state, so it is unit tested on the JVM. [seekTo] takes a player position in us;
 * [onChange] receives each edited sidecar, to persist it. [durationUs] is null until the player knows it.
 * The zoom lives here, not in the player, so stepping frames keeps it.
 */
class ReviewState(
    sidecar: Sidecar,
    durationUs: Long?,
    private val seekTo: (Long) -> Unit,
    private val onChange: (Sidecar) -> Unit,
) {
    var sidecar by mutableStateOf(sidecar)
        private set
    var clock by mutableStateOf(clockFor(sidecar, durationUs))
        private set
    var durationKnown by mutableStateOf(durationUs != null)
        private set
    var frameIndex by mutableIntStateOf(0)
        private set
    var selected: Int? by mutableStateOf(null)
        private set
    var zoomPan by mutableStateOf(ZoomPan())
        private set

    init {
        if (sidecar.crossings.isNotEmpty()) selectMark(0) else goTo(0)
    }

    /** Last frame index of the clip, or the last mark's frame (at least 1) while the duration is unknown. */
    val lastIndex: Int
        get() = if (durationKnown) clock.lastIndex else maxOf(1, sidecar.crossings.maxOfOrNull { clock.indexOfPts(it.ptsUs) } ?: 1)

    /** Frame index of mark [index]. */
    fun markFrame(index: Int): Int = clock.indexOfPts(sidecar.crossings[index].ptsUs)

    fun onDuration(durationUs: Long) {
        clock = clockFor(sidecar, durationUs)
        durationKnown = true
        frameIndex = clock.step(frameIndex, 0)
    }

    /** The player moved by itself (not through this state). */
    fun onPosition(positionUs: Long) {
        frameIndex = clock.indexAt(positionUs)
    }

    fun step(by: Int) = goTo(clock.step(frameIndex, by))

    /** Scrubber: [fraction] of the clip, 0..1. */
    fun scrubTo(fraction: Float) = goTo((fraction.coerceIn(0f, 1f) * lastIndex).roundToInt().coerceAtMost(lastIndex))

    fun previousFinisher() {
        sidecar.previousCrossing(clock, frameIndex)?.let(::selectMark)
    }

    fun nextFinisher() {
        sidecar.nextCrossing(clock, frameIndex)?.let(::selectMark)
    }

    /** Selects mark [index] and shows its frame; null clears the selection. */
    fun selectMark(index: Int?) {
        selected = index
        if (index != null) goTo(markFrame(index))
    }

    fun addMarkHere() {
        val existing = sidecar.markAt(clock, frameIndex)
        if (existing != null) {
            selected = existing
            return
        }
        edit(sidecar.withCrossingAdded(clock.ptsOf(frameIndex), frameIndex))
        selected = sidecar.markAt(clock, frameIndex)
    }

    fun moveSelectedHere() {
        val index = selected ?: return
        val onFrame = sidecar.markAt(clock, frameIndex)
        if (onFrame != null && onFrame != index) return
        edit(sidecar.withCrossingMoved(index, clock.ptsOf(frameIndex), frameIndex))
        selected = sidecar.markAt(clock, frameIndex)
    }

    fun deleteSelected() {
        val index = selected ?: return
        edit(sidecar.withCrossingRemoved(index))
        selected = null
    }

    fun applyGesture(centroid: Vec2, panDelta: Vec2, zoomFactor: Float, viewport: Vec2) {
        zoomPan = zoomPan.applyGesture(centroid, panDelta, zoomFactor, viewport)
    }

    private fun goTo(index: Int) {
        frameIndex = index
        seekTo(clock.positionUs(index))
    }

    private fun edit(edited: Sidecar) {
        sidecar = edited
        onChange(edited)
    }

    private companion object {
        fun clockFor(sidecar: Sidecar, durationUs: Long?) = if (durationUs == null) {
            FrameClock(sidecar.fps, sidecar.firstFramePtsUs)
        } else {
            FrameClock.forDuration(sidecar.fps, sidecar.firstFramePtsUs, durationUs)
        }
    }
}
