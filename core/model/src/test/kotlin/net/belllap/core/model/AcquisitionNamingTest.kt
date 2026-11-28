package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AcquisitionNamingTest {
    @Test
    fun firstFinishOfALabelIsNumberOne() {
        assertEquals("2026-11-28 Finish 1", AcquisitionNaming.nextName("2026-11-28", emptyList()))
    }

    @Test
    fun numberingCountsPerLabelAndContinuesAfterTheHighest() {
        val existing = listOf("2026-11-28 Finish 1", "2026-11-28 Finish 3", "Crit A Finish 7", "2026-11-28 Finish x")
        assertEquals("2026-11-28 Finish 4", AcquisitionNaming.nextName("2026-11-28", existing))
        assertEquals("Crit A Finish 8", AcquisitionNaming.nextName("Crit A", existing))
        assertEquals("Crit B Finish 1", AcquisitionNaming.nextName("Crit B", existing))
    }

    @Test
    fun aLabelThatPrefixesAnotherDoesNotShareItsNumbers() {
        assertEquals("Crit Finish 1", AcquisitionNaming.nextName("Crit", listOf("Crit A Finish 5")))
    }
}
