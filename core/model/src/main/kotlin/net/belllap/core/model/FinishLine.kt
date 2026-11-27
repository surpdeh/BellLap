package net.belllap.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A point in normalised coordinates: 0..1 across the frame's width (x) and height (y). */
@Serializable
data class NormPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "NormPoint out of range: $x,$y" }
    }

    fun clamped(): NormPoint = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    companion object {
        /** Builds a point, clamping out-of-range input (a finger dragged past the frame edge). */
        fun clampOf(x: Float, y: Float) = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}

/**
 * The finish line as two endpoints in **normalised sensor coordinates**: the unrotated frame the sensor
 * delivers, origin top-left. It survives display rotation and resolution changes (F11).
 */
@Serializable
data class FinishLine(val start: NormPoint, val end: NormPoint) {
    /** True when the endpoints are far enough apart (normalised) to define a usable line. */
    fun isUsable(minLength: Float = MIN_LENGTH): Boolean {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return dx * dx + dy * dy >= minLength * minLength
    }

    /** Pixel endpoints for a frame of [width] x [height]. */
    fun toPixels(width: Int, height: Int): List<Pair<Float, Float>> =
        listOf(start.x * width to start.y * height, end.x * width to end.y * height)

    fun toSidecarJson(): String = SIDECAR_JSON.encodeToString(this)

    companion object {
        const val MIN_LENGTH = 0.05f
        private val SIDECAR_JSON = Json { encodeDefaults = true }

        fun fromSidecarJson(text: String): FinishLine? =
            runCatching { SIDECAR_JSON.decodeFromString<FinishLine>(text) }.getOrNull()
    }
}

/**
 * Maps between normalised sensor coordinates and normalised displayed-preview coordinates.
 * [rotationDegrees] is the clockwise rotation (0/90/180/270) applied to the sensor image for display;
 * [mirrored] flips the displayed image horizontally (front camera).
 */
@Suppress("MagicNumber")
data class PreviewMapping(val rotationDegrees: Int = 0, val mirrored: Boolean = false) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotation must be 0/90/180/270" }
    }

    fun sensorToDisplay(p: NormPoint): NormPoint {
        val (rx, ry) = when (rotationDegrees) {
            90 -> 1f - p.y to p.x
            180 -> 1f - p.x to 1f - p.y
            270 -> p.y to 1f - p.x
            else -> p.x to p.y
        }
        return NormPoint.clampOf(if (mirrored) 1f - rx else rx, ry)
    }

    fun displayToSensor(p: NormPoint): NormPoint {
        val x = if (mirrored) 1f - p.x else p.x
        val y = p.y
        val (sx, sy) = when (rotationDegrees) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
        return NormPoint.clampOf(sx, sy)
    }

    fun sensorToDisplay(line: FinishLine) = FinishLine(sensorToDisplay(line.start), sensorToDisplay(line.end))

    fun displayToSensor(line: FinishLine) = FinishLine(displayToSensor(line.start), displayToSensor(line.end))
}
