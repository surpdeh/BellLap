package net.belllap.vision.flag

import net.belllap.testing.fixtures.Fixtures
import net.belllap.vision.core.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChequeredFlagDetectorTest {
    private val ns = 1_000_000_000L

    /** (ptsNs, visible, result) for each frame of a fixture. */
    private fun run(fixture: net.belllap.testing.fixtures.Fixture, config: FlagConfig = FlagConfig()) =
        ChequeredFlagDetector(config).let { d -> fixture.frames!!.map { it.ptsNs to d.accept(it) } }

    @Test
    fun seesTheWavedFlagWithinOneSecondAndLosesIt() {
        val r = run(Fixtures.flag())
        val seen = r.first { it.second.visible }.first
        assertTrue("seen at $seen", seen in 4 * ns..5 * ns)
        val lost = r.first { it.first > 9 * ns && !it.second.visible }.first
        assertTrue("lost at $lost", lost in 9 * ns..11 * ns)
        assertTrue(r.any { it.first > 15 * ns && it.second.visible })
        assertEquals(2, r.zipWithNext().count { (a, b) -> !a.second.visible && b.second.visible })
    }

    @Test
    fun flagBoxCoversTheFlag() {
        val box = run(Fixtures.flag()).first { it.first > 6 * ns }.second.box
        assertNotNull(box)
        val flagX = 100
        val flagY = 8
        assertTrue(box!!.x >= flagX - 16 && box.x + box.width <= flagX + 48 + 16)
        assertTrue(box.y >= flagY - 8 && box.y + box.height <= flagY + 32 + 8)
    }

    @Test
    fun staticBannerIsChequeredButNeverTheFlag() {
        val banner = Fixtures.banner()
        val r = run(banner)
        assertTrue("no flag fixture frames", r.isNotEmpty())
        assertTrue("banner texture should be found", r.any { it.second.textureBox != null })
        assertTrue(r.none { it.second.visible })
        assertTrue(r.all { it.second.box == null })
    }

    @Test
    fun blankSceneIsQuiet() {
        val r = run(Fixtures.crossings())
        assertTrue(r.none { it.second.visible || it.second.textureBox != null })
    }

    @Test
    fun briefDropoutDoesNotFlipVisible() {
        val d = ChequeredFlagDetector()
        val frames = Fixtures.flag().frames!!
        val blank = frames[0] // flagless and static
        var drops = 0
        frames.forEach { f ->
            val gap = f.ptsNs in 6 * ns..(6 * ns + 400_000_000L)
            val shown = if (gap) Frame(f.ptsNs, blank.width, blank.height, blank.stride, blank.luma) else f
            val visible = d.accept(shown).visible
            if (f.ptsNs in 5 * ns..8 * ns && !visible) drops++
        }
        assertEquals(0, drops)
    }

    @Test
    fun roiExcludingTheFlagFindsNothing() {
        val roi = FlagRoi(0.0, 0.5, 0.6, 1.0)
        assertTrue(run(Fixtures.flag(), FlagConfig(roi = roi)).none { it.second.visible })
        assertNull(run(Fixtures.flag(), FlagConfig(roi = roi)).last().second.box)
    }
}
