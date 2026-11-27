package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCapabilitiesTest {
    private fun sizes(vararg s: Triple<Int, Int, Int>) = s.map { SizeFps(it.first, it.second, it.third) }

    private val back = CameraProfile(
        id = "0",
        facing = CameraFacing.BACK,
        hardwareLevel = "FULL",
        fpsRanges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(30, 60)),
        useCases = listOf(
            UseCaseOutputs(
                UseCase.PREVIEW,
                sizes(Triple(1280, 720, 60), Triple(1920, 1080, 30), Triple(3840, 2160, 30)),
            ),
            // Analysis cannot output 2160p here, so the combination cannot offer it.
            UseCaseOutputs(UseCase.ANALYSIS, sizes(Triple(1280, 720, 60), Triple(1920, 1080, 30))),
            UseCaseOutputs(UseCase.VIDEO, sizes(Triple(1920, 1080, 60))),
        ),
        highSpeed = listOf(HighSpeedOption(1280, 720, listOf(FpsRange(120, 120)))),
    )
    private val caps = DeviceCapabilities(
        cameras = listOf(back),
        audioSources = listOf(AudioSourceSupport("MIC", true), AudioSourceSupport("UNPROCESSED", false)),
        codecs = listOf(CodecSupport("video/avc", encoder = true, name = "c2.android.avc.encoder", hardware = false)),
    )

    @Test
    fun jsonRoundTripsEveryField() {
        assertEquals(caps, DeviceCapabilities.fromJson(caps.toJson()))
    }

    @Test
    fun jsonNamesTheProbedFacts() {
        val json = caps.toJson()
        listOf("hardwareLevel", "fpsRanges", "highSpeed", "audioSources", "codecs", "\"schema\": 1").forEach {
            assert(json.contains(it)) { "missing $it in $json" }
        }
    }

    @Test
    fun sessionGatingKeepsOnlySizesBothUseCasesOutput() {
        val c = checkNotNull(caps.forFacing(CameraFacing.BACK))
        assertEquals(listOf(Resolution.HD_720, Resolution.FHD_1080), c.supportedResolutions())
    }

    @Test
    fun sessionGatingOffersSixtyOnlyWhenASizeReachesIt() {
        val c = checkNotNull(caps.forFacing(CameraFacing.BACK))
        assertEquals(listOf(Fps.FPS_30, Fps.FPS_60), c.supportedFps())
        val slow = back.copy(useCases = back.useCases.map { u -> u.copy(sizes = u.sizes.map { it.copy(maxFps = 30) }) })
        val s = checkNotNull(caps.copy(cameras = listOf(slow)).forFacing(CameraFacing.BACK))
        assertEquals(listOf(Fps.FPS_30), s.supportedFps())
    }

    @Test
    fun facingsListOnlyCamerasTheDeviceHas() {
        assertEquals(setOf(CameraFacing.BACK), caps.facings())
        assertNull(caps.forFacing(CameraFacing.FRONT))
    }

    @Test
    fun frameDurationConvertsToFps() {
        assertEquals(30, fpsFromMinFrameDurationNs(33_333_333))
        assertEquals(60, fpsFromMinFrameDurationNs(16_666_666))
        assertEquals(0, fpsFromMinFrameDurationNs(0))
    }
}
