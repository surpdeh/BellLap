package net.belllap.core.model

enum class CameraFacing { BACK, FRONT }

/** Capture resolution by frame height; 16:9 landscape is assumed. */
@Suppress("MagicNumber")
enum class Resolution(val width: Int, val height: Int) {
    HD_720(1280, 720),
    FHD_1080(1920, 1080),
    UHD_2160(3840, 2160),
}

@Suppress("MagicNumber")
enum class Fps(val value: Int) { FPS_30(30), FPS_60(60) }

/** User-chosen capture and automation settings (F10). Pure data, persisted by the app module. */
data class CaptureSettings(
    val facing: CameraFacing = CameraFacing.BACK,
    val resolution: Resolution = Resolution.FHD_1080,
    val fps: Fps = Fps.FPS_30,
    val bellAutomation: Boolean = true,
    val flagAutomation: Boolean = true,
    val crossingBeeps: Boolean = true,
    val flagTimeoutSeconds: Int = DEFAULT_FLAG_TIMEOUT_S,
    /** Alert volume 0..100 that overrides the system volume, or null to follow the system. */
    val alertVolumePercent: Int? = null,
    /** The finish line in normalised sensor coordinates (F11), or null when not drawn yet. */
    val finishLine: FinishLine? = null,
) {
    init {
        require(flagTimeoutSeconds in FLAG_TIMEOUT_RANGE_S) { "flagTimeoutSeconds out of range" }
        require(
            alertVolumePercent == null || alertVolumePercent in VOLUME_RANGE_PCT,
        ) { "alertVolumePercent out of range" }
    }

    /** Returns these settings with resolution and fps replaced by the nearest option [caps] supports. */
    fun coerceTo(caps: CameraCapabilities): CaptureSettings {
        val resolutions = caps.supportedResolutions()
        if (resolutions.isEmpty()) return this
        val res = if (resolution in resolutions) {
            resolution
        } else {
            resolutions.lastOrNull { it.height <= resolution.height } ?: resolutions.first()
        }
        val fpsOptions = caps.supportedFps()
        val newFps = if (fps in fpsOptions || fpsOptions.isEmpty()) fps else fpsOptions.last { it.value <= fps.value }
        return copy(resolution = res, fps = newFps)
    }

    companion object {
        const val DEFAULT_FLAG_TIMEOUT_S = 60
        val FLAG_TIMEOUT_RANGE_S = 10..600
        val VOLUME_RANGE_PCT = 0..100
    }
}

data class OutputSize(val width: Int, val height: Int)

/** What the selected camera can do, probed from Camera2 characteristics by `:capture`. */
data class CameraCapabilities(
    val outputSizes: List<OutputSize>,
    val fpsRanges: List<IntRange>,
) {
    /** Resolutions with at least one matching output size, ascending. */
    fun supportedResolutions(): List<Resolution> =
        Resolution.entries.filter { r -> outputSizes.any { it.width == r.width && it.height == r.height } }

    /** Frame rates a target range can hold exactly (a range covering the rate), ascending. */
    fun supportedFps(): List<Fps> = Fps.entries.filter { f -> fpsRanges.any { f.value in it } }
}
