package net.belllap.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import net.belllap.core.model.AudioSourceSupport
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CameraProfile
import net.belllap.core.model.CodecSupport
import net.belllap.core.model.DeviceCapabilities
import net.belllap.core.model.FpsRange
import net.belllap.core.model.HighSpeedOption
import net.belllap.core.model.SizeFps
import net.belllap.core.model.UseCase
import net.belllap.core.model.UseCaseOutputs
import net.belllap.core.model.fpsFromMinFrameDurationNs
import java.io.File

/** Reads what the device can do from Camera2, `AudioManager` and `MediaCodecList` (F25). Needs no permission. */
object DeviceProbe {
    const val FILE_NAME = "device-capabilities.json"

    fun probe(context: Context): DeviceCapabilities = DeviceCapabilities(
        cameras = probeCameras(context.getSystemService(CameraManager::class.java)),
        audioSources = probeAudioSources(context),
        codecs = probeCodecs(),
    )

    /** Probes and writes the JSON next to the event logs, where the bug report bundle picks it up. */
    fun writeJson(context: Context, dir: File): DeviceCapabilities {
        val caps = probe(context)
        dir.mkdirs()
        File(dir, FILE_NAME).writeText(caps.toJson())
        return caps
    }

    private fun probeCameras(manager: CameraManager?): List<CameraProfile> = manager?.cameraIdList.orEmpty().mapNotNull {
        runCatching { profile(it, manager!!.getCameraCharacteristics(it)) }.getOrNull()
    }

    private fun profile(id: String, c: CameraCharacteristics): CameraProfile? {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
            else -> return null // external cameras are not a setting we offer
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return CameraProfile(
            id = id,
            facing = facing,
            hardwareLevel = hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            fpsRanges = ranges.map(::fpsRange).sortedWith(compareBy({ it.max }, { it.min })),
            useCases = map?.let(::useCases).orEmpty(),
            highSpeed = map?.let(::highSpeed).orEmpty(),
        )
    }

    /** PREVIEW is a SurfaceTexture, ANALYSIS is YUV_420_888 and VIDEO is a MediaRecorder surface. */
    private fun useCases(map: StreamConfigurationMap) = listOf(
        UseCaseOutputs(
            UseCase.PREVIEW,
            sizesWithFps(map.getOutputSizes(android.graphics.SurfaceTexture::class.java)) {
                map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, it)
            },
        ),
        UseCaseOutputs(
            UseCase.ANALYSIS,
            sizesWithFps(map.getOutputSizes(ImageFormat.YUV_420_888)) {
                map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it)
            },
        ),
        UseCaseOutputs(
            UseCase.VIDEO,
            sizesWithFps(map.getOutputSizes(MediaRecorder::class.java)) {
                map.getOutputMinFrameDuration(MediaRecorder::class.java, it)
            },
        ),
    )

    private fun sizesWithFps(sizes: Array<Size>?, minDurationNs: (Size) -> Long) =
        sizes.orEmpty()
            .map { SizeFps(it.width, it.height, fpsFromMinFrameDurationNs(minDurationNs(it))) }
            .sortedWith(compareBy({ it.width * it.height }, { it.width }))

    private fun highSpeed(map: StreamConfigurationMap) = map.highSpeedVideoSizes.orEmpty().map { size ->
        HighSpeedOption(
            size.width,
            size.height,
            map.getHighSpeedVideoFpsRangesFor(size).map(::fpsRange).sortedBy { it.max },
        )
    }

    private fun fpsRange(r: Range<Int>) = FpsRange(r.lower, r.upper)

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private fun probeAudioSources(context: Context): List<AudioSourceSupport> {
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED).toBoolean()
        // The other sources are part of every Android build we target; only UNPROCESSED is optional.
        return listOf(
            AudioSourceSupport("MIC", true),
            AudioSourceSupport("UNPROCESSED", unprocessed),
            AudioSourceSupport("CAMCORDER", true),
            AudioSourceSupport("VOICE_RECOGNITION", true),
            AudioSourceSupport("VOICE_COMMUNICATION", true),
        )
    }

    private val CODEC_MIMES = listOf("video/avc", "video/hevc", "audio/mp4a-latm")

    private fun probeCodecs(): List<CodecSupport> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { info ->
            info.supportedTypes.filter { it in CODEC_MIMES }.map {
                CodecSupport(it, encoder = true, name = info.name, hardware = info.isHardwareAccelerated)
            }
        }
        .sortedWith(compareBy({ it.mime }, { it.name }))
}
