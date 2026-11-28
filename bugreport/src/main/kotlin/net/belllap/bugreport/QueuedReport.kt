package net.belllap.bugreport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class StoredAttachment(val name: String, val kind: AttachmentKind, val contentType: String, val size: Long)

/** One presigned PUT from the Worker. [headers] are sent exactly as given; [expiresAt] is ISO-8601. */
@Serializable
data class UploadTarget(
    val name: String,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: String,
) {
    val expiresAtMs: Long get() = Instant.parse(expiresAt).toEpochMilli()
}

@Serializable
data class IssueRef(val number: Int, val url: String)

/**
 * Where a report is in its life. Every transition is derived from this persisted value, so a crash or retry at any
 * point resumes instead of restarting: [Pending] -> [Created] -> [Filed], or [Failed] holding the state to resume from.
 */
@Serializable
sealed interface ReportState {
    @Serializable
    @SerialName("pending")
    data object Pending : ReportState

    /** The Worker knows the report. [uploaded] are the names whose PUT has succeeded. */
    @Serializable
    @SerialName("created")
    data class Created(
        val reportId: String,
        val uploads: List<UploadTarget>,
        val uploaded: Set<String> = emptySet(),
    ) : ReportState

    @Serializable
    @SerialName("filed")
    data class Filed(val issue: IssueRef) : ReportState

    /** [retryable] means the queue resumes it on its own; otherwise only the user's Retry does, from [resumeFrom]. */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String, val retryable: Boolean, val resumeFrom: ReportState) : ReportState
}

/** The device fields the Worker requires; [installId] is a UUID stable per install. */
@Serializable
data class ReportContext(val installId: String, val appVersion: String, val device: String)

/** A report accepted by [ReportDraft.validate], persisted as `report.json` beside its attachment files. */
@Serializable
data class QueuedReport(
    val localId: String,
    val createdAtMs: Long,
    val context: ReportContext,
    val description: String,
    val attachments: List<StoredAttachment>,
    val state: ReportState = ReportState.Pending,
) {
    val isFinished: Boolean
        get() = state is ReportState.Filed || (state is ReportState.Failed && !state.retryable)

    val totalBytes: Long get() = attachments.sumOf { it.size }
}
