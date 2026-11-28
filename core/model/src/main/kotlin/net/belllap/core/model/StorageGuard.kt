package net.belllap.core.model

/** Outcome of [StorageGuard.check]: whether a new recording may start, and the reason to show when it may not. */
data class StorageCheck(val ok: Boolean, val freeBytes: Long, val requiredBytes: Long) {
    /** User-facing refusal, or null when recording may start. */
    val message: String? =
        if (ok) null else "Not enough free storage to record: ${mb(freeBytes)} MB free, ${mb(requiredBytes)} MB needed."

    private fun mb(bytes: Long) = bytes / BYTES_PER_MB

    private companion object {
        const val BYTES_PER_MB = 1_000_000L
    }
}

/**
 * Free-storage guard (F17): a recording is refused below [StorageGuard.requiredBytes], the encoder bitrate for the
 * chosen resolution and fps times a minimum recording time. Bitrates follow docs/research section F (1080p60 HEVC at
 * 20-30 Mbps); CameraX picks its own bitrate, so these are deliberately on the high side.
 */
@Suppress("MagicNumber")
object StorageGuard {
    const val MIN_MINUTES = 5
    private const val SECONDS_PER_MINUTE = 60L
    private const val BITS_PER_BYTE = 8L

    /** Estimated encoder bitrate in bits per second. */
    fun bitrateBps(resolution: Resolution, fps: Fps): Long {
        val base = when (resolution) {
            Resolution.HD_720 -> 10_000_000L
            Resolution.FHD_1080 -> 20_000_000L
            Resolution.UHD_2160 -> 60_000_000L
        }
        return if (fps == Fps.FPS_60) base * 3 / 2 else base
    }

    fun requiredBytes(resolution: Resolution, fps: Fps, minMinutes: Int = MIN_MINUTES): Long =
        bitrateBps(resolution, fps) / BITS_PER_BYTE * SECONDS_PER_MINUTE * minMinutes

    fun check(freeBytes: Long, resolution: Resolution, fps: Fps, minMinutes: Int = MIN_MINUTES): StorageCheck {
        val required = requiredBytes(resolution, fps, minMinutes)
        return StorageCheck(freeBytes >= required, freeBytes, required)
    }
}
