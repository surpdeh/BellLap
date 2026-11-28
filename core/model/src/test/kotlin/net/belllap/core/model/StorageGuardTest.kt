package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    @Test
    fun requiredBytesIsBitrateTimesMinimumMinutes() {
        // 1080p30 is 20 Mbps = 2.5 MB/s; 5 minutes = 750 MB.
        assertEquals(750_000_000L, StorageGuard.requiredBytes(Resolution.FHD_1080, Fps.FPS_30))
        assertEquals(750_000_000L * 3 / 2, StorageGuard.requiredBytes(Resolution.FHD_1080, Fps.FPS_60))
    }

    @Test
    fun higherResolutionAndFpsNeedMoreSpace() {
        val sizes = Resolution.entries.map { StorageGuard.requiredBytes(it, Fps.FPS_30) }
        assertEquals(sizes.sorted(), sizes)
        val hd = Resolution.HD_720
        assertTrue(StorageGuard.requiredBytes(hd, Fps.FPS_60) > StorageGuard.requiredBytes(hd, Fps.FPS_30))
    }

    @Test
    fun refusesBelowTheThresholdAndExplainsWhy() {
        val required = StorageGuard.requiredBytes(Resolution.FHD_1080, Fps.FPS_30)
        val enough = StorageGuard.check(required, Resolution.FHD_1080, Fps.FPS_30)
        assertTrue(enough.ok)
        assertNull(enough.message)
        val short = StorageGuard.check(required - 1, Resolution.FHD_1080, Fps.FPS_30)
        assertFalse(short.ok)
        assertEquals("Not enough free storage to record: 749 MB free, 750 MB needed.", short.message)
    }
}
