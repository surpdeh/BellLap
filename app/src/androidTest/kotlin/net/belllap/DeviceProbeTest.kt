package net.belllap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.belllap.capture.DeviceProbe
import net.belllap.core.model.DeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F25 on a real OS: probes the emulator and saves the JSON as PR evidence (`files/evidence/`). */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {
    @Test
    fun probesTheDeviceAndSavesJson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val caps = DeviceProbe.writeJson(context, TestEvidence.directory())
        val saved = TestEvidence.directory().resolve(DeviceProbe.FILE_NAME).readText()
        assertEquals(caps, DeviceCapabilities.fromJson(saved))
        assertTrue("MIC is always available", caps.audioSources.any { it.name == "MIC" && it.supported })
        assertTrue("an AVC encoder is required to record", caps.codecs.any { it.mime == "video/avc" })
        caps.cameras.forEach { assertTrue("camera ${it.id} lists fps ranges", it.fpsRanges.isNotEmpty()) }
    }
}
