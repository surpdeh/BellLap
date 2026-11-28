package net.belllap.ui

import net.belllap.bugreport.AttachmentKind
import net.belllap.bugreport.QueuedReport
import net.belllap.bugreport.ReportState
import net.belllap.bugreport.UploadProgress

sealed interface BellUi {
    data object None : BellUi

    data object Recording : BellUi

    data class Recorded(val seconds: Float) : BellUi
}

data class CandidateUi(val label: String, val centerUs: Long)

data class VideoRowUi(
    val name: String,
    val sizeText: String,
    val selected: Boolean,
    val centerUs: Long,
    val durationUs: Long,
    val candidates: List<CandidateUi>,
)

data class BugReportFormUi(
    val description: String = "",
    val bell: BellUi = BellUi.None,
    val bellAvailable: Boolean = true,
    val videos: List<VideoRowUi> = emptyList(),
    val problems: List<String> = emptyList(),
    val preparing: Boolean = false,
    val queuedCount: Int = 0,
)

data class ConsentItemUi(val name: String, val kind: AttachmentKind, val bytes: Long)

data class BugReportConsentUi(
    val description: String,
    val items: List<ConsentItemUi>,
    val configured: Boolean,
    val sending: Boolean = false,
) {
    val totalBytes: Long get() = items.sumOf { it.bytes }
    val hasVideo: Boolean get() = items.any { it.kind == AttachmentKind.VIDEO }
}

enum class ReportStatus { WAITING, UPLOADING, FAILED, FILED }

data class ReportRowUi(
    val localId: String,
    val title: String,
    val status: ReportStatus,
    val detail: String,
    val progress: Float? = null,
    val issueUrl: String? = null,
    val canRetry: Boolean = false,
)

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val MAX_TITLE = 60

fun formatBytes(bytes: Long): String = when {
    bytes >= MIB -> "%.1f MB".format(bytes.toDouble() / MIB)
    bytes >= KIB -> "%d KB".format(bytes / KIB)
    else -> "$bytes B"
}

fun kindLabel(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.CAPABILITIES -> "Device capabilities"
    AttachmentKind.TRACE -> "Trace bundle (logs, events, sidecars)"
    AttachmentKind.BELL -> "Bell recording"
    AttachmentKind.VIDEO -> "Video clip"
}

fun reportRow(report: QueuedReport, progress: UploadProgress?): ReportRowUi {
    val title = report.description.lineSequence().first().take(MAX_TITLE)
    val state = report.state
    return when {
        state is ReportState.Filed -> ReportRowUi(
            report.localId,
            title,
            ReportStatus.FILED,
            "Filed as issue #${state.issue.number}",
            issueUrl = state.issue.url,
        )
        state is ReportState.Failed && state.retryable -> ReportRowUi(
            report.localId,
            title,
            ReportStatus.FAILED,
            "Could not send yet (${state.message}). It will retry when the network is back.",
            canRetry = true,
        )
        state is ReportState.Failed -> ReportRowUi(
            report.localId,
            title,
            ReportStatus.FAILED,
            "Failed: ${state.message}",
            canRetry = true,
        )
        progress != null && progress.total > 0 -> ReportRowUi(
            report.localId,
            title,
            ReportStatus.UPLOADING,
            "Uploading ${progress.name}: ${formatBytes(progress.sent)} of ${formatBytes(progress.total)}",
            progress = progress.sent.toFloat() / progress.total,
        )
        else -> ReportRowUi(
            report.localId,
            title,
            ReportStatus.WAITING,
            "Waiting to upload (${formatBytes(report.totalBytes)})",
        )
    }
}
