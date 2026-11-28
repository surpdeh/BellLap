package net.belllap.testing.harness

import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.vision.core.Frame
import kotlin.math.abs

/*
 * A deliberately simple reference detector. It exists so the replay harness has something to measure before the real
 * finish-line detector (F18), which replaces it and must beat the same thresholds. The bell baseline was replaced by
 * the F14 cascade in `:audio:bell` and the flag baseline by F16 in `:vision:flag`. Pure Kotlin: it only sees
 * [Frame], never a clock.
 */

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
