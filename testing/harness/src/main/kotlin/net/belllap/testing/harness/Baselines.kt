package net.belllap.testing.harness

import net.belllap.audio.source.PcmChunk
import net.belllap.testing.fixtures.BellAudioGenerator
import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.vision.core.Frame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Deliberately simple reference detectors. They exist so the replay harness has something to measure before the real
 * detectors (F-bell, F-flag, F-finishline) land; those replace these and must beat the same thresholds.
 * Pure Kotlin: they only see [PcmChunk] and [Frame], never a clock.
 */
class BaselineBellDetector(private val sampleRate: Int) {
    private val bins = BellAudioGenerator.PARTIALS.map { it * BellAudioGenerator.F0 }
    private var lastOnsetNs = Long.MIN_VALUE
    private var prevRatio = 0.0
    val onsetsNs = ArrayList<Long>()

    /** Feeds one chunk (about 20 ms). Narrowband energy at the bell's partials over total energy marks a strike. */
    fun accept(chunk: PcmChunk) {
        val x = chunk.samples
        if (x.isEmpty()) return
        val total = x.sumOf { it.toDouble() * it } / x.size
        if (total < MIN_POWER) return
        val tonal = bins.sumOf { goertzelPower(x, it) }
        val ratio = tonal / total
        val rising = ratio > ON_RATIO && prevRatio <= ON_RATIO
        prevRatio = ratio
        if (rising && (lastOnsetNs == Long.MIN_VALUE || chunk.ptsNs - lastOnsetNs > REFRACTORY_NS)) {
            onsetsNs += chunk.ptsNs
            lastOnsetNs = chunk.ptsNs
        }
    }

    private fun goertzelPower(x: ShortArray, hz: Double): Double {
        val coeff = 2 * cos(2 * PI * hz / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s = v + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return 2 * power / x.size / x.size
    }

    companion object {
        const val ON_RATIO = 0.25
        const val MIN_POWER = 1.0
        const val REFRACTORY_NS = 3_000_000_000L
    }
}

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
