package net.belllap.bugreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface EnqueueResult {
    data class Queued(val report: QueuedReport) : EnqueueResult

    data class Invalid(val problems: List<DraftProblem>) : EnqueueResult
}

enum class ProcessResult { DONE, RETRY_LATER }

/** The file-backed queue of reports. UI observes [reports] and [progress]; a worker calls [process]. */
class ReportQueue(
    private val store: ReportStore,
    api: ReportApi,
    tokens: AppCheckTokenSource,
    private val nowMs: () -> Long,
    policy: RetryPolicy = RetryPolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val items = MutableStateFlow(store.load())
    private val progressByReport = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    private val uploader = ReportUploader(api, tokens, store, nowMs, policy) { id, p ->
        progressByReport.update { it + (id to p) }
    }

    val reports: StateFlow<List<QueuedReport>> = items

    /** The file being sent per report id, while it is being sent. */
    val progress: StateFlow<Map<String, UploadProgress>> = progressByReport

    /** True when [process] has something to do. */
    val hasWork: Boolean get() = items.value.any { !it.isFinished }

    /** Copies the draft's files into the report's directory, so the form's temp files can go. */
    suspend fun enqueue(draft: ReportDraft, context: ReportContext): EnqueueResult {
        val problems = draft.validate()
        if (problems.isNotEmpty()) return EnqueueResult.Invalid(problems)
        val report = QueuedReport(
            localId = newId(),
            createdAtMs = nowMs(),
            context = context,
            description = draft.description.trim(),
            attachments = draft.attachments.map { StoredAttachment(it.name, it.kind, it.contentType, it.size) },
        )
        withContext(Dispatchers.IO) {
            draft.attachments.forEach {
                it.file.copyTo(store.attachmentFile(report.localId, it.name), overwrite = true)
            }
            store.save(report)
        }
        items.update { it + report }
        return EnqueueResult.Queued(report)
    }

    /** Puts a failed report back to the state it failed from. The caller then schedules [process]. */
    fun retry(localId: String) = replace(localId) { r ->
        val failed = r.state as? ReportState.Failed
        failed?.let { r.copy(state = it.resumeFrom) } ?: r
    }

    fun discard(localId: String) {
        store.discard(localId)
        items.update { list -> list.filterNot { it.localId == localId } }
        progressByReport.update { it - localId }
    }

    /** Resumes every unfinished report in order; [ProcessResult.RETRY_LATER] when one waits on network or server. */
    suspend fun process(): ProcessResult = mutex.withLock {
        var later = false
        for (report in items.value.filterNot { it.isFinished }) {
            val result = uploader.run(report)
            if (!store.isDiscarded(result.localId)) replace(result.localId) { result }
            progressByReport.update { it - result.localId }
            if ((result.state as? ReportState.Failed)?.retryable == true) later = true
        }
        if (later) ProcessResult.RETRY_LATER else ProcessResult.DONE
    }

    private fun replace(localId: String, change: (QueuedReport) -> QueuedReport) {
        items.update { list -> list.map { if (it.localId == localId) change(it).also(store::save) else it } }
    }
}
