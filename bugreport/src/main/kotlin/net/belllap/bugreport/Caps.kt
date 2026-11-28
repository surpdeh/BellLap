package net.belllap.bugreport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentKind {
    @SerialName("capabilities")
    CAPABILITIES,

    @SerialName("trace")
    TRACE,

    @SerialName("bell")
    BELL,

    @SerialName("video")
    VIDEO,
    ;

    /** The `kind` value the Worker expects. */
    val wire: String get() = name.lowercase()
}

class KindCaps(val maxBytes: Long, val maxCount: Int, val contentTypes: Set<String>)

/** Mirrors `backend/worker/src/caps.ts`; `CapsDriftTest` fails when the two disagree. */
object Caps {
    private const val MIB = 1024L * 1024

    const val MAX_ATTACHMENTS = 12
    const val MAX_TOTAL_BYTES = 200L * MIB
    const val MAX_DESCRIPTION = 8000

    private val byKind = mapOf(
        AttachmentKind.CAPABILITIES to KindCaps(1 * MIB, 1, setOf("application/json")),
        AttachmentKind.TRACE to KindCaps(
            10 * MIB,
            8,
            setOf("application/json", "application/x-ndjson", "text/plain", "application/zip", "application/gzip"),
        ),
        AttachmentKind.BELL to KindCaps(10 * MIB, 1, setOf("audio/wav", "audio/mp4", "audio/ogg")),
        AttachmentKind.VIDEO to KindCaps(100 * MIB, 2, setOf("video/mp4")),
    )

    fun of(kind: AttachmentKind): KindCaps = byKind.getValue(kind)
}
