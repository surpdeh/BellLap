package net.belllap.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where a crossing came from: the live detector, the post-process refinement pass, or a manual edit in review. */
@Serializable
enum class CrossingSource {
    @SerialName("live")
    LIVE,

    @SerialName("refined")
    REFINED,

    @SerialName("manual")
    MANUAL,
}

@Serializable
data class SidecarDevice(val manufacturer: String, val model: String, val sdkInt: Int)

@Serializable
data class SidecarCrossing(val ptsUs: Long, val frameIndex: Int, val confidence: Float, val source: CrossingSource)

/** An interval during which the chequered flag was visible, by frame PTS. */
@Serializable
data class FlagInterval(val startPtsUs: Long, val endPtsUs: Long)

/**
 * Sidecar `acq_<id>.json` next to each acquisition MP4 (F17). Everything is keyed by PTS, never wall-clock time.
 * [firstFramePtsUs] is the camera-clock PTS of the first recorded frame, so review can map a crossing PTS to a seek
 * position as `ptsUs - firstFramePtsUs`. The committed JSON schema is `docs/sidecar.schema.json`.
 */
@Serializable
data class Sidecar(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val videoFile: String,
    val device: SidecarDevice,
    val fps: Int,
    val width: Int,
    val height: Int,
    val line: FinishLine? = null,
    val firstFramePtsUs: Long,
    val crossings: List<SidecarCrossing> = emptyList(),
    val flagIntervals: List<FlagInterval> = emptyList(),
    val appVersion: String,
) {
    /** Human-readable problems with this sidecar; empty when it is valid. */
    fun validate(): List<String> = buildList {
        if (schemaVersion != SCHEMA_VERSION) add("schemaVersion must be $SCHEMA_VERSION")
        if (id.isBlank()) add("id is blank")
        if (name.isBlank()) add("name is blank")
        if (videoFile.isBlank()) add("videoFile is blank")
        if (fps <= 0) add("fps must be positive")
        if (width <= 0 || height <= 0) add("width and height must be positive")
        if (firstFramePtsUs < 0) add("firstFramePtsUs must not be negative")
        crossings.forEachIndexed { i, c ->
            if (c.ptsUs < 0) add("crossings[$i].ptsUs must not be negative")
            if (c.frameIndex < 0) add("crossings[$i].frameIndex must not be negative")
            if (c.confidence !in 0f..1f) add("crossings[$i].confidence must be within 0..1")
        }
        flagIntervals.forEachIndexed { i, f ->
            if (f.startPtsUs < 0 || f.endPtsUs < f.startPtsUs) add("flagIntervals[$i] must have 0 <= start <= end")
        }
    }

    fun encode(): String = STRICT_JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1

        private val STRICT_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            explicitNulls = true
        }

        /** Strict decode: malformed JSON, unknown or missing fields and invalid values all throw. */
        fun decode(text: String): Sidecar {
            val sidecar = try {
                STRICT_JSON.decodeFromString<Sidecar>(text)
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException, as is a failed init { require } check.
                throw SidecarFormatException("Malformed sidecar: ${e.message}", e)
            }
            val problems = sidecar.validate()
            if (problems.isNotEmpty()) throw SidecarFormatException("Invalid sidecar: ${problems.joinToString("; ")}")
            return sidecar
        }
    }
}

class SidecarFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
