package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishLineTest {
    private val line = FinishLine(NormPoint(0.25f, 0.1f), NormPoint(0.75f, 0.9f))

    @Test
    fun displayMappingRoundTripsForEveryRotationAndMirror() {
        for (rot in listOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                val m = PreviewMapping(rot, mirrored)
                val back = m.displayToSensor(m.sensorToDisplay(line))
                assertEquals("rot=$rot mirrored=$mirrored", line.start.x, back.start.x, EPS)
                assertEquals(line.start.y, back.start.y, EPS)
                assertEquals(line.end.x, back.end.x, EPS)
                assertEquals(line.end.y, back.end.y, EPS)
            }
        }
    }

    @Test
    fun rotation90MapsSensorTopLeftToDisplayTopRight() {
        val p = PreviewMapping(90).sensorToDisplay(NormPoint(0f, 0f))
        assertEquals(NormPoint(1f, 0f), p)
    }

    @Test
    fun mirrorFlipsX() {
        assertEquals(NormPoint(0.75f, 0.1f), PreviewMapping(0, mirrored = true).sensorToDisplay(NormPoint(0.25f, 0.1f)))
    }

    @Test
    fun clampOfKeepsDraggedPointInsideFrame() {
        assertEquals(NormPoint(1f, 0f), NormPoint.clampOf(1.4f, -0.2f))
        assertThrows(IllegalArgumentException::class.java) { NormPoint(1.1f, 0f) }
    }

    @Test
    fun usabilityRejectsTinyLines() {
        assertTrue(line.isUsable())
        assertFalse(FinishLine(NormPoint(0.5f, 0.5f), NormPoint(0.51f, 0.5f)).isUsable())
    }

    @Test
    fun pixelsScaleWithResolutionButNormalisedGeometryDoesNot() {
        assertEquals(listOf(480f to 108f, 1440f to 972f), line.toPixels(1920, 1080))
        assertEquals(listOf(320f to 72f, 960f to 648f), line.toPixels(1280, 720))
    }

    @Test
    fun sidecarJsonRoundTrips() {
        val json = line.toSidecarJson()
        assertEquals("""{"start":{"x":0.25,"y":0.1},"end":{"x":0.75,"y":0.9}}""", json)
        assertEquals(line, FinishLine.fromSidecarJson(json))
        assertNull(FinishLine.fromSidecarJson("{not json"))
        assertNull(FinishLine.fromSidecarJson("""{"start":{"x":2,"y":0},"end":{"x":0,"y":0}}"""))
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
