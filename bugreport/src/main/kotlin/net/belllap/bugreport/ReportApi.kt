package net.belllap.bugreport

import java.io.File

/** What the Worker's create call needs. [attachments] declare the exact byte size of each file to upload. */
data class CreateRequest(
    val context: ReportContext,
    val description: String,
    val attachments: List<StoredAttachment>,
)

class CreatedReport(val reportId: String, val uploads: List<UploadTarget>)

/** The documented outcomes of `POST /v1/reports/<id>/submit`; other statuses are an [ApiException]. */
sealed interface SubmitResult {
    data class Filed(val issue: IssueRef) : SubmitResult

    data class UploadsMissing(val names: List<String>) : SubmitResult

    data class SizeMismatch(val names: List<String>) : SubmitResult

    data object InProgress : SubmitResult

    data object GithubFailed : SubmitResult
}

/** A non-success HTTP answer. Network failures are plain `IOException`s, so the two are told apart. */
class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

/** Supplies the `X-Firebase-AppCheck` value. Throws an `IOException` when there is none, so the upload retries. */
fun interface AppCheckTokenSource {
    suspend fun token(): String
}

interface ReportApi {
    suspend fun create(appCheckToken: String, request: CreateRequest): CreatedReport

    /** PUTs [file] exactly as [target] says. [onProgress] gets the bytes sent so far. */
    suspend fun put(target: UploadTarget, file: File, onProgress: (Long) -> Unit)

    suspend fun submit(appCheckToken: String, reportId: String): SubmitResult
}
