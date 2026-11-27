package net.belllap.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What this device can do, probed once by `:capture` (F25). Pure data: serialised to JSON for the debug screen
 * and bug reports, and queried by the settings screen so it only offers options the hardware supports.
 */
@Serializable
data class DeviceCapabilities(
    val cameras: List<CameraProfile>,
    val audioSources: List<AudioSourceSupport>,
    val codecs: List<CodecSupport>,
    val schema: Int = SCHEMA,
) {
    fun camera(facing: CameraFacing): CameraProfile? = cameras.firstOrNull { it.facing == facing }

    /** Facings the device has a camera for. */
    fun facings(): Set<CameraFacing> = cameras.map { it.facing }.toSet()

    /**
     * The gating view of [facing]'s camera: what a preview plus analysis session can hold. A size counts only when
     * both use cases can output it, and a frame rate only when a size reaches it.
     */
    fun forFacing(facing: CameraFacing): CameraCapabilities? = camera(facing)?.sessionCapabilities()

    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        const val SCHEMA = 1
        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun fromJson(text: String): DeviceCapabilities = JSON.decodeFromString(text)
    }
}

@Serializable
data class CameraProfile(
    val id: String,
    val facing: CameraFacing,
    /** `INFO_SUPPORTED_HARDWARE_LEVEL`: LEGACY, LIMITED, FULL, LEVEL_3 or EXTERNAL. */
    val hardwareLevel: String,
    /** `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. */
    val fpsRanges: List<FpsRange>,
    /** Output sizes and their best frame rate for each use-case set member. */
    val useCases: List<UseCaseOutputs>,
    /** Constrained high-speed sizes and their fps ranges. Empty when the camera has no high-speed mode. */
    val highSpeed: List<HighSpeedOption>,
) {
    fun outputs(useCase: UseCase): List<SizeFps> = useCases.firstOrNull { it.useCase == useCase }?.sizes.orEmpty()

    fun sessionCapabilities(): CameraCapabilities {
        val analysis = outputs(UseCase.ANALYSIS).map { it.width to it.height }.toSet()
        val sizes = outputs(UseCase.PREVIEW).filter { (it.width to it.height) in analysis }
        val ranges = fpsRanges.filter { range -> sizes.any { it.maxFps >= range.max } }
        return CameraCapabilities(
            outputSizes = sizes.map { OutputSize(it.width, it.height) },
            fpsRanges = ranges.map { it.min..it.max },
        )
    }
}

/** The use cases the app binds together: PREVIEW and ANALYSIS in setup and standby, PREVIEW and VIDEO when recording. */
enum class UseCase { PREVIEW, ANALYSIS, VIDEO }

@Serializable
data class FpsRange(val min: Int, val max: Int)

@Serializable
data class SizeFps(val width: Int, val height: Int, val maxFps: Int)

@Serializable
data class UseCaseOutputs(val useCase: UseCase, val sizes: List<SizeFps>)

@Serializable
data class HighSpeedOption(val width: Int, val height: Int, val fpsRanges: List<FpsRange>)

@Serializable
data class AudioSourceSupport(val name: String, val supported: Boolean)

@Serializable
data class CodecSupport(val mime: String, val encoder: Boolean, val name: String, val hardware: Boolean)

/** Frame rate a stream holds, from the camera's minimum frame duration in nanoseconds; 0 when unknown. */
fun fpsFromMinFrameDurationNs(durationNs: Long): Int = if (durationNs <= 0) 0 else (NANOS_PER_SECOND / durationNs).toInt()

private const val NANOS_PER_SECOND = 1_000_000_000L
