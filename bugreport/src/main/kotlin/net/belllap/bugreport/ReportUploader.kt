package net.belllap.bugreport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

class UploadProgress(val name: String, val sent: Long, val total: Long)

/** Why a step is retried; decides the wait. */
enum class RetryReason { NETWORK, IN_PROGRESS, RATE_LIMITED, SERVER, REJECTED, UPLOADS_MISSING }

/** Injected so tests run on virtual time. At most [maxRetries] retries per run; the queue's caller retries later. */
class RetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun delayMs(attempt: Int, reason: RetryReason): Long = when (reason) {
        RetryReason.UPLOADS_MISSING -> 0L
        RetryReason.IN_PROGRESS -> IN_PROGRESS_MS
        RetryReason.RATE_LIMITED -> RATE_LIMIT_MS
        else -> minOf(CAP_MS, BASE_MS shl (attempt - 1).coerceAtMost(MAX_SHIFT))
    }

    private companion object {
        const val DEFAULT_MAX_RETRIES = 8
        const val BASE_MS = 2_000L
        const val CAP_MS = 60_000L
        const val IN_PROGRESS_MS = 5_000L
        const val RATE_LIMIT_MS = 60_000L
        const val MAX_SHIFT = 10
    }
}

private sealed interface Outcome {
    data class Progressed(val report: QueuedReport) : Outcome

    data class Retry(val reason: RetryReason, val report: QueuedReport, val why: String) : Outcome

    data class Terminal(val message: String) : Outcome
}

/**
 * Drives one [QueuedReport] to Filed. Each iteration reads the persisted state, does one step and persists the result,
 * so a crash or a retry at any point converges: only names not yet uploaded are sent, and a repeat submit returns the
 * same issue.
 */
class ReportUploader(
    private val api: ReportApi,
    private val tokens: AppCheckTokenSource,
    private val store: ReportStore,
    private val nowMs: () -> Long,
    private val policy: RetryPolicy = RetryPolicy(),
    private val onProgress: (localId: String, UploadProgress) -> Unit = { _, _ -> },
) {
    suspend fun run(initial: QueuedReport): QueuedReport {
        val failure = initial.state as? ReportState.Failed
        return when {
            failure == null -> drive(initial)
            failure.retryable -> drive(initial.copy(state = failure.resumeFrom))
            else -> initial
        }
    }

    private suspend fun drive(start: QueuedReport): QueuedReport {
        var report = start
        var retries = 0
        var halted: QueuedReport? = null
        while (halted == null && report.state !is ReportState.Filed && !store.isDiscarded(report.localId)) {
            when (val outcome = step(report)) {
                is Outcome.Progressed -> {
                    report = outcome.report
                    store.save(report)
                    if (report.state is ReportState.Filed) store.deleteAttachments(report.localId)
                }
                is Outcome.Retry -> {
                    retries++
                    report = outcome.report
                    if (retries > policy.maxRetries) {
                        halted = fail(report, outcome.why, retryable = true)
                    } else {
                        store.save(report)
                        policy.sleep(policy.delayMs(retries, outcome.reason))
                    }
                }
                is Outcome.Terminal -> halted = fail(report, outcome.message, retryable = false)
            }
        }
        return halted ?: report
    }

    private suspend fun step(report: QueuedReport): Outcome = try {
        advance(report)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Retry(RetryReason.NETWORK, report, e.message ?: "network error")
    } catch (e: ApiException) {
        classify(e, report)
    }

    private fun fail(report: QueuedReport, message: String, retryable: Boolean): QueuedReport =
        report.copy(state = ReportState.Failed(message, retryable, report.state)).also(store::save)

    private suspend fun advance(report: QueuedReport): Outcome = when (val state = report.state) {
        ReportState.Pending -> create(report)
        is ReportState.Created -> upload(report, state)
        is ReportState.Filed -> Outcome.Progressed(report)
        is ReportState.Failed -> Outcome.Progressed(report.copy(state = state.resumeFrom))
    }

    private suspend fun create(report: QueuedReport): Outcome {
        val request = CreateRequest(report.context, report.description, report.attachments)
        val created = api.create(tokens.token(), request)
        val missing = report.attachments.map { it.name } - created.uploads.map { it.name }.toSet()
        if (missing.isNotEmpty()) return Outcome.Terminal("The server gave no upload for ${missing.joinToString()}.")
        val state = ReportState.Created(created.reportId, created.uploads)
        return Outcome.Progressed(report.copy(state = state))
    }

    private suspend fun upload(report: QueuedReport, state: ReportState.Created): Outcome {
        val todo = state.uploads.filter { it.name !in state.uploaded }
        val target = todo.firstOrNull()
        return when {
            todo.any { it.expiresAtMs <= nowMs() } -> Outcome.Progressed(report.copy(state = ReportState.Pending))
            target == null -> submit(report, state)
            else -> put(report, state, target)
        }
    }

    private suspend fun put(report: QueuedReport, state: ReportState.Created, target: UploadTarget): Outcome {
        val file = store.attachmentFile(report.localId, target.name)
        val total = file.length()
        return if (!file.isFile) {
            Outcome.Terminal("The attachment ${target.name} is no longer on this device.")
        } else {
            try {
                api.put(target, file) { onProgress(report.localId, UploadProgress(target.name, it, total)) }
                Outcome.Progressed(report.copy(state = state.copy(uploaded = state.uploaded + target.name)))
            } catch (e: ApiException) {
                Outcome.Retry(RetryReason.REJECTED, report.copy(state = ReportState.Pending), e.message.orEmpty())
            }
        }
    }

    private suspend fun submit(report: QueuedReport, state: ReportState.Created): Outcome =
        when (val result = api.submit(tokens.token(), state.reportId)) {
            is SubmitResult.Filed -> Outcome.Progressed(report.copy(state = ReportState.Filed(result.issue)))
            is SubmitResult.UploadsMissing -> redo(report, state, result.names, "The server is missing uploads.")
            is SubmitResult.SizeMismatch -> redo(report, state, result.names, "The server rejected upload sizes.")
            SubmitResult.InProgress -> Outcome.Retry(RetryReason.IN_PROGRESS, report, "The server is still filing it.")
            SubmitResult.GithubFailed -> Outcome.Retry(RetryReason.SERVER, report, "GitHub did not accept the issue.")
        }

    private fun redo(report: QueuedReport, state: ReportState.Created, names: List<String>, why: String): Outcome {
        val next = state.copy(uploaded = state.uploaded - names.toSet())
        return Outcome.Retry(RetryReason.UPLOADS_MISSING, report.copy(state = next), why)
    }
}

private fun classify(e: ApiException, report: QueuedReport): Outcome = when (e.status) {
    HTTP_UNAUTHORIZED -> Outcome.Terminal("The server did not accept this app's App Check token. Try again later.")
    HTTP_BAD_REQUEST -> Outcome.Terminal("The server rejected the report as invalid.")
    HTTP_PAYLOAD_TOO_LARGE -> Outcome.Terminal("The report request was too large.")
    HTTP_NOT_FOUND ->
        if (report.state is ReportState.Created) {
            val fresh = report.copy(state = ReportState.Pending)
            Outcome.Retry(RetryReason.UPLOADS_MISSING, fresh, "Report unknown; starting again.")
        } else {
            Outcome.Terminal("The bug report service was not found.")
        }
    HTTP_TOO_MANY -> Outcome.Retry(RetryReason.RATE_LIMITED, report, "Too many reports; waiting.")
    in HTTP_SERVER_ERROR..HTTP_SERVER_ERROR_END -> Outcome.Retry(RetryReason.SERVER, report, e.message.orEmpty())
    else -> Outcome.Terminal(e.message.orEmpty())
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_TOO_MANY = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_SERVER_ERROR_END = 599
