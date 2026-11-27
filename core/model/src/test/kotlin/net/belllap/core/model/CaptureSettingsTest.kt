package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureSettingsTest {
    private val caps = CameraCapabilities(
        outputSizes = listOf(OutputSize(1280, 720), OutputSize(1920, 1080), OutputSize(640, 480)),
        fpsRanges = listOf(15..30, 30..30),
    )

    @Test
    fun capabilitiesListOnlyMatchingResolutionsAndFps() {
        assertEquals(listOf(Resolution.HD_720, Resolution.FHD_1080), caps.supportedResolutions())
        assertEquals(listOf(Fps.FPS_30), caps.supportedFps())
        assertEquals(listOf(Fps.FPS_30, Fps.FPS_60), caps.copy(fpsRanges = listOf(30..60)).supportedFps())
    }

    @Test
    fun coerceFallsBackToNearestLowerSupportedOption() {
        val out = CaptureSettings(resolution = Resolution.UHD_2160, fps = Fps.FPS_60).coerceTo(caps)
        assertEquals(Resolution.FHD_1080, out.resolution)
        assertEquals(Fps.FPS_30, out.fps)
    }

    @Test
    fun coerceKeepsSupportedChoicesAndIgnoresEmptyProbe() {
        val s = CaptureSettings(resolution = Resolution.HD_720)
        assertEquals(s, s.coerceTo(caps))
        assertEquals(s, s.coerceTo(CameraCapabilities(emptyList(), emptyList())))
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { CaptureSettings(flagTimeoutSeconds = 5) }
        assertThrows(IllegalArgumentException::class.java) { CaptureSettings(alertVolumePercent = 101) }
    }
}
