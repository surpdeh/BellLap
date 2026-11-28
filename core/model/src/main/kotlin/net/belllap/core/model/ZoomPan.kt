package net.belllap.core.model

import kotlin.math.abs

data class Vec2(val x: Float, val y: Float)

/**
 * Review zoom and pan (F19). [scale] is 1..8; [panX] and [panY] are the translation in pixels of content scaled about
 * the viewport centre, clamped so the content always covers the viewport. It belongs to the review state, not the
 * player, so stepping frames never resets it.
 */
data class ZoomPan(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    /**
     * Applies one gesture step: [zoomFactor] about [centroid] (viewport coordinates, origin top left), then
     * [panDelta] in pixels. The content point under the centroid stays under it.
     */
    fun applyGesture(centroid: Vec2, panDelta: Vec2, zoomFactor: Float, viewport: Vec2): ZoomPan {
        val s = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = s / scale
        val dx = centroid.x - viewport.x / 2
        val dy = centroid.y - viewport.y / 2
        return ZoomPan(
            s,
            clampPan(dx - (dx - panX) * ratio + panDelta.x, s, viewport.x),
            clampPan(dy - (dy - panY) * ratio + panDelta.y, s, viewport.y),
        )
    }

    private fun clampPan(pan: Float, s: Float, size: Float): Float {
        val max = abs((s - 1f) * size / 2)
        return pan.coerceIn(-max, max) + 0f // + 0f turns -0f into 0f
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
    }
}
