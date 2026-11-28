package net.belllap.vision.finishline

import net.belllap.testing.fixtures.SceneRenderer
import net.belllap.testing.fixtures.SceneSpec
import net.belllap.vision.core.Frame
import net.belllap.vision.core.LumaImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.tan
import kotlin.random.Random

class WhiteLineDetectorTest {
    private fun scene() = SceneRenderer.render(SceneSpec(seed = 1, frameCount = 1, banner = true)).single()

    /** Grey road with an optional white line from (x0,y0) to (x1,y1), drawn [lineWidth] px thick, plus noise. */
    private fun still(
        w: Int,
        h: Int,
        line: List<Int>? = null,
        lineWidth: Int = 3,
        bg: Int = 110,
        white: Int = 235,
        seed: Int = 3,
    ): Frame {
        val rng = Random(seed)
        val luma = ByteArray(w * h) { (bg + rng.nextInt(-3, 4)).toByte() }
        if (line != null) {
            val x0 = line[0]
            val y0 = line[1]
            val x1 = line[2]
            val y1 = line[3]
            for (y in y0..y1) {
                val cx = x0 + (x1 - x0) * (y - y0).toDouble() / (y1 - y0)
                for (x in (cx - lineWidth / 2.0).toInt()..(cx + lineWidth / 2.0).toInt()) {
                    if (x in 0 until w) luma[y * w + x] = white.toByte()
                }
            }
        }
        return Frame(0, w, h, w, luma)
    }

    @Test
    fun findsTheSyntheticSceneLine() {
        val s = assertNotNull(WhiteLineDetector.detect(scene()))
        // The renderer draws x = 79..81 from row 60 to the bottom of a 160x120 frame.
        assertEquals(0.5f, s.line.start.x, 0.02f)
        assertEquals(0.5f, s.line.end.x, 0.02f)
        assertEquals(0.5f, s.line.start.y, 0.03f)
        assertEquals(1.0f, s.line.end.y, 0.03f)
        assertTrue("confidence ${s.confidence}", s.confidence > 0.6f)
        save("scene", scene(), s)
    }

    @Test
    fun findsATiltedFullHeightLineAtHighResolution() {
        // 1280x720, tilted 10 degrees (dx = 720*tan(10deg) = ~127 px), right of centre.
        val dx = (720 * tan(Math.toRadians(10.0))).toInt()
        val f = still(1280, 720, listOf(700, 0, 700 + dx, 719), lineWidth = 8)
        val s = assertNotNull(WhiteLineDetector.detect(f))
        assertEquals((700 + 0) / 1280f, s.line.start.x, 0.02f)
        assertEquals((700 + dx) / 1280f, s.line.end.x, 0.02f)
        assertTrue(s.confidence > 0.7f)
        save("tilted_720p", f, s)
    }

    @Test
    fun ignoresLinesTiltedBeyondTwentyDegrees() {
        val dx = (120 * tan(Math.toRadians(45.0))).toInt()
        assertNull(WhiteLineDetector.detect(still(320, 120, listOf(100, 0, 100 + dx, 119))))
    }

    @Test
    fun prefersTheLongerOfTwoLines() {
        val f = still(320, 240, listOf(80, 100, 80, 160))
        val both = Frame(
            0,
            320,
            240,
            320,
            f.luma.copyOf().also { l ->
                for (y in 20 until 230) for (x in 238..241) l[y * 320 + x] = 235.toByte()
            },
        )
        val s = assertNotNull(WhiteLineDetector.detect(both))
        assertEquals(240f / 320, s.line.start.x, 0.02f)
        save("two_lines", both, s)
    }

    @Test
    fun noLineGivesNull() {
        assertNull(WhiteLineDetector.detect(still(160, 120)))
    }

    @Test
    fun darkSceneGivesNull() {
        assertNull(WhiteLineDetector.detect(still(160, 120, listOf(80, 10, 80, 110), bg = 20, white = 90)))
    }

    @Test
    fun shortWhiteMarkIsRejected() {
        assertNull(WhiteLineDetector.detect(still(160, 120, listOf(80, 50, 80, 58))))
    }

    @Test
    fun washedOutFrameGivesNull() {
        assertNull(WhiteLineDetector.detect(still(160, 120, bg = 240)))
    }

    @Test
    fun brokenLineIsScoredBelowSolidLine() {
        val solid = still(320, 240, listOf(160, 20, 160, 220))
        val broken = Frame(
            0,
            320,
            240,
            320,
            solid.luma.copyOf().also { l ->
                for (y in 90 until 150) for (x in 150..170) l[y * 320 + x] = 110.toByte()
            },
        )
        val a = assertNotNull(WhiteLineDetector.detect(solid))
        val b = WhiteLineDetector.detect(broken)
        assertTrue(b == null || b.confidence < a.confidence)
    }

    private fun <T> assertNotNull(v: T?): T {
        org.junit.Assert.assertNotNull(v)
        return v!!
    }

    /** Before/after overlay for the PR: the still beside the same still with the suggestion in red. */
    private fun save(name: String, f: Frame, s: LineSuggestion) {
        val dir = File(System.getProperty("belllap.overlayDir") ?: "build/reports/f12").also { it.mkdirs() }
        val before = LumaImages.toImage(f)
        val after = BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB)
        val g = after.createGraphics()
        g.drawImage(before, 0, 0, null)
        g.color = Color.RED
        g.stroke = java.awt.BasicStroke(maxOf(1f, f.width / 320f))
        val (p, q) = s.line.toPixels(f.width, f.height)
        g.drawLine(p.first.toInt(), p.second.toInt(), q.first.toInt(), q.second.toInt())
        g.dispose()
        val both = BufferedImage(f.width * 2 + 4, f.height, BufferedImage.TYPE_INT_RGB)
        both.createGraphics().apply {
            drawImage(before, 0, 0, null)
            drawImage(after, f.width + 4, 0, null)
            dispose()
        }
        javax.imageio.ImageIO.write(both, "png", File(dir, "${name}_before_after_c${(s.confidence * 100).toInt()}.png"))
    }
}
