package net.belllap.testing.fixtures

import net.belllap.vision.core.Frame
import net.belllap.vision.core.ImageSequenceFrameSource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** A rider whose front wheel reaches [LineScene.lineX] exactly at [crossFrame]; [group] is the bunch number. */
class RiderSpec(val crossFrame: Int, val group: Int)

/**
 * A tiny synthetic camera view: grey road with a white finish line, optional waving chequered flag, an optional
 * static chequered banner (the distractor) and riders crossing left to right. Rendering is a pure function of the
 * spec and the seed, so every machine produces identical pixels.
 */
class SceneSpec(
    val seed: Long,
    val frameCount: Int,
    val flagIntervalsS: List<Pair<Double, Double>> = emptyList(),
    val banner: Boolean = true,
    val riders: List<RiderSpec> = emptyList(),
    val fps: Double = FPS,
) {
    companion object {
        const val WIDTH = 160
        const val HEIGHT = 120
        const val FPS = 30.0
    }
}

object SceneRenderer {
    const val LINE_X = 80
    const val LINE_WIDTH = 3
    const val ROAD_TOP = 60
    const val LANE_TOP = 64
    const val LANE_PITCH = 14
    const val LANE_HEIGHT = 12
    const val LANES = 3
    const val RIDER_LENGTH = 20
    const val RIDER_SPEED = 4
    const val FLAG_X = 100
    const val FLAG_Y = 8
    const val FLAG_W = 48
    const val FLAG_H = 32
    private const val CELL = 8
    private const val DARK = 25
    private const val LIGHT = 235
    private const val ROAD = 110
    private const val SKY = 170
    private const val NOISE_SIGMA = 1.5
    private const val BANNER_Y = 50
    private const val BANNER_H = 8
    private const val RIDER_VALUE = 30
    private const val WAVE_HZ = 2.0
    private const val SHEAR_PX = 6.0
    private const val SHEAR_PERIOD = 12.0

    fun lane(rider: Int) = rider % LANES

    fun render(spec: SceneSpec): List<Frame> {
        val rng = Random(spec.seed)
        return List(spec.frameCount) { f -> renderFrame(spec, f, rng) }
    }

    fun flagActive(spec: SceneSpec, frame: Int): Boolean {
        val t = frame / spec.fps
        return spec.flagIntervalsS.any { t >= it.first && t < it.second }
    }

    private fun renderFrame(spec: SceneSpec, f: Int, rng: Random): Frame {
        val w = SceneSpec.WIDTH
        val h = SceneSpec.HEIGHT
        val px = IntArray(w * h) { i -> if (i / w < ROAD_TOP) SKY else ROAD }
        for (y in ROAD_TOP until h) for (x in LINE_X - LINE_WIDTH / 2..LINE_X + LINE_WIDTH / 2) px[y * w + x] = LIGHT
        if (spec.banner) drawBanner(px, w)
        if (flagActive(spec, f)) drawFlag(px, w, f / spec.fps)
        spec.riders.forEachIndexed { i, r -> drawRider(px, w, i, f - r.crossFrame) }
        val ptsNs = ImageSequenceFrameSource.ptsOf(f, spec.fps)
        val luma = ByteArray(w * h) { (px[it] + (Noise.gaussian(rng) * NOISE_SIGMA).toInt()).coerceIn(0, 255).toByte() }
        return Frame(ptsNs, w, h, w, luma)
    }

    /** A sponsor banner: chequered like the flag but static, so it must not look like a waving flag. */
    private fun drawBanner(px: IntArray, w: Int) {
        for (y in BANNER_Y until BANNER_Y + BANNER_H) for (x in 0 until FLAG_X - CELL) px[y * w + x] = checker(x, y)
    }

    private fun drawFlag(px: IntArray, w: Int, tS: Double) {
        for (y in 0 until FLAG_H) {
            val shear = (SHEAR_PX * sin(2 * PI * WAVE_HZ * tS + y / SHEAR_PERIOD * 2 * PI)).toInt()
            for (x in 0 until FLAG_W) {
                px[(FLAG_Y + y) * w + FLAG_X + x] = checker(x + shear + CELL * 4, y)
            }
        }
    }

    private fun checker(x: Int, y: Int): Int {
        val parity = (Math.floorDiv(x, CELL) + Math.floorDiv(y, CELL)) and 1
        return if (parity == 0) DARK else LIGHT
    }

    /** [rel] is frames since the crossing frame. Leading edge = LINE_X + rel * speed, covering RIDER_LENGTH px. */
    private fun drawRider(px: IntArray, w: Int, index: Int, rel: Int) {
        val lead = LINE_X + rel * RIDER_SPEED
        val top = LANE_TOP + lane(index) * LANE_PITCH
        for (x in maxOf(0, lead - RIDER_LENGTH + 1)..minOf(w - 1, lead)) {
            for (y in top until top + LANE_HEIGHT) px[y * w + x] = RIDER_VALUE
        }
    }
}
