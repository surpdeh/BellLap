package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomPanTest {
    private val viewport = Vec2(1000f, 500f)
    private val centre = Vec2(500f, 250f)
    private val none = Vec2(0f, 0f)

    @Test
    fun startsUnzoomed() {
        assertEquals(1f, ZoomPan().scale, 0f)
    }

    @Test
    fun scaleIsClampedBetweenOneAndEight() {
        assertEquals(8f, ZoomPan().applyGesture(centre, none, 100f, viewport).scale, 0f)
        assertEquals(1f, ZoomPan().applyGesture(centre, none, 0.1f, viewport).scale, 0f)
    }

    @Test
    fun cannotPanWhileUnzoomed() {
        val z = ZoomPan().applyGesture(centre, Vec2(300f, -200f), 1f, viewport)
        assertEquals(0f, z.panX, 0f)
        assertEquals(0f, z.panY, 0f)
    }

    @Test
    fun panIsClampedSoTheContentCoversTheViewport() {
        val z = ZoomPan(2f).applyGesture(centre, Vec2(5000f, -5000f), 1f, viewport)
        assertEquals(500f, z.panX, 0.001f) // (2 - 1) * 1000 / 2
        assertEquals(-250f, z.panY, 0.001f) // (2 - 1) * 500 / 2
    }

    @Test
    fun zoomKeepsTheContentUnderTheCentroidFixed() {
        val c = Vec2(750f, 250f)
        val z = ZoomPan().applyGesture(c, none, 2f, viewport)
        // The content point under the centroid before is at offset 250 right of the centre; it must stay there.
        val q = 250f // content offset from the centre, scale 1
        assertEquals(c.x - 500f, q * z.scale + z.panX, 0.001f)
    }

    @Test
    fun zoomingBackOutRecentres() {
        val z = ZoomPan().applyGesture(Vec2(900f, 100f), none, 4f, viewport).applyGesture(centre, none, 0.01f, viewport)
        assertEquals(ZoomPan(), z)
    }

    @Test
    fun shrinkingReclampsAnExistingPan() {
        val z = ZoomPan(4f, 1500f, 0f).applyGesture(centre, none, 0.5f, viewport)
        assertEquals(2f, z.scale, 0.001f)
        assertEquals(500f, z.panX, 0.001f)
    }
}
