package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FlagIntervalTrackerTest {
    @Test
    fun recordsEachVisibleSpellByPts() {
        val t = FlagIntervalTracker(startPtsUs = 1_000, visible = true)
        t.setVisible(false, 5_000)
        t.setVisible(true, 9_000)
        t.setVisible(true, 9_500) // repeated value ignored
        assertEquals(listOf(FlagInterval(1_000, 5_000), FlagInterval(9_000, 12_000)), t.finish(12_000))
    }

    @Test
    fun manualStartWithNoFlagHasNoIntervalUntilItAppears() {
        val t = FlagIntervalTracker(startPtsUs = 0, visible = false)
        assertEquals(emptyList<FlagInterval>(), FlagIntervalTracker(0, false).finish(100))
        t.setVisible(true, 40)
        assertEquals(listOf(FlagInterval(40, 70)), t.finish(70))
    }

    @Test
    fun aBackwardsPtsIsClampedToAnEmptyInterval() {
        val t = FlagIntervalTracker(500, true)
        t.setVisible(false, 400)
        assertEquals(listOf(FlagInterval(500, 500)), t.finish(600))
    }
}
