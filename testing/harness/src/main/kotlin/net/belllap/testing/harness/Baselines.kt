package net.belllap.testing.harness

import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.vision.core.Frame
import kotlin.math.abs

/*
 * Deliberately simple reference detectors. They exist so the replay harness has something to measure before the real
 * flag and finish-line detectors (F16, F18) land; those replace these and must beat the same thresholds. The bell
 * baseline was replaced by the F14 cascade in `:audio:bell`. Pure Kotlin: they only see [Frame], never a clock.
 */

/** Flags a waving flag: enough pixels changing a lot between frames for long enough (0.3% area, 0.5 s). */
class BaselineFlagDetector(
    private val minAreaFraction: Double = 0.003,
    private val minDurationNs: Long = 500_000_000L,
) {
    private var prev: Frame? = null
    private var runStartNs = -1L
    private var lastActiveNs = 0L
    private var open = false
    private var openStartNs = 0L
    val intervals = ArrayList<LongRange>()

    fun accept(frame: Frame) {
        val p = prev
        prev = frame
        if (p == null) return
        var changed = 0
        for (i in 0 until frame.width * frame.height) {
            if (abs((frame.luma[i].toInt() and 0xFF) - (p.luma[i].toInt() and 0xFF)) > DIFF) changed++
        }
        val active = changed >= minAreaFraction * frame.width * frame.height
        if (active) {
            if (runStartNs < 0) runStartNs = frame.ptsNs
            lastActiveNs = frame.ptsNs
            if (!open && frame.ptsNs - runStartNs >= minDurationNs) {
                open = true
                openStartNs = runStartNs
            }
        } else if (frame.ptsNs - lastActiveNs > GAP_NS) {
            close()
        }
    }

    fun finish(): List<LongRange> {
        close()
        return intervals
    }

    private fun close() {
        if (open) intervals += openStartNs..lastActiveNs
        open = false
        runStartNs = -1
    }

    private companion object {
        const val DIFF = 60
        const val GAP_NS = 300_000_000L
    }
}

/** Finish-line crossings: a rising edge of "differs from background" on the line column, per lane band. */
class BaselineCrossingDetector(private val lineX: Int = SceneRenderer.LINE_X) {
    private var background: IntArray? = null
    private val wasOn = BooleanArray(SceneRenderer.LANES)
    val crossingsNs = ArrayList<Long>()

    fun accept(frame: Frame) {
        val bg = background ?: IntArray(frame.height) { frame.lumaAt(lineX, it) }.also { background = it }
        for (lane in 0 until SceneRenderer.LANES) {
            val top = SceneRenderer.LANE_TOP + lane * SceneRenderer.LANE_PITCH
            val rows = top until top + SceneRenderer.LANE_HEIGHT
            val covered = rows.count { abs(frame.lumaAt(lineX, it) - bg[it]) > DIFF }
            val on = covered * 2 > SceneRenderer.LANE_HEIGHT
            if (on && !wasOn[lane]) crossingsNs += frame.ptsNs
            wasOn[lane] = on
        }
    }

    private companion object {
        const val DIFF = 40
    }
}
