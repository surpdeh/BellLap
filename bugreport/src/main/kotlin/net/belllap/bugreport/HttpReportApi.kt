package net.belllap.bugreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** The F23 client over `HttpURLConnection`. [baseUrl] is the Worker's URL without a trailing slash. */
class HttpReportApi(private val baseUrl: String) : ReportApi {
    @Serializable
    private class AttachmentDto(val name: String, val kind: String, val contentType: String, val size: Long)

    @Serializable
    private class CreateDto(
        val installId: String,
        val appVersion: String,
        val device: String,
        val description: String,
        val attachments: List<AttachmentDto>,
    )

    @Serializable
    private class CreatedDto(val reportId: String, val uploads: List<UploadTarget>)

    @Serializable
    private class SubmitDto(
        val issue: IssueRef? = null,
        val error: String? = null,
        val missing: List<String> = emptyList(),
        val names: List<String> = emptyList(),
    )

    private class Response(val status: Int, val body: String)

    override suspend fun create(appCheckToken: String, request: CreateRequest): CreatedReport {
        val dto = CreateDto(
            request.context.installId,
            request.context.appVersion,
            request.context.device,
            request.description,
            request.attachments.map { AttachmentDto(it.name, it.kind.wire, it.contentType, it.size) },
        )
        val response = post("$baseUrl/v1/reports", appCheckToken, JSON.encodeToString(dto))
        if (response.status != HTTP_CREATED) throw failure(response)
        val created = decode<CreatedDto>(response)
        return CreatedReport(created.reportId, created.uploads)
    }

    override suspend fun submit(appCheckToken: String, reportId: String): SubmitResult {
        val response = post("$baseUrl/v1/reports/$reportId/submit", appCheckToken, null)
        val body = runCatching { JSON.decodeFromString<SubmitDto>(response.body) }.getOrNull()
        return when {
            response.status in listOf(HTTP_OK, HTTP_CREATED) && body?.issue != null -> SubmitResult.Filed(body.issue)
            response.status == HTTP_CONFLICT && body?.error == "uploads_missing" ->
                SubmitResult.UploadsMissing(body.missing)
            response.status == HTTP_CONFLICT && body?.error == "in_progress" -> SubmitResult.InProgress
            response.status == HTTP_UNPROCESSABLE && body?.error == "size_mismatch" ->
                SubmitResult.SizeMismatch(body.names)
            response.status == HTTP_BAD_GATEWAY -> SubmitResult.GithubFailed
            else -> throw failure(response)
        }
    }

    override suspend fun put(target: UploadTarget, file: File, onProgress: (Long) -> Unit) {
        val size = file.length()
        val declared = target.headers.entries.firstOrNull { it.key.equals(LENGTH, true) }?.value?.toLongOrNull()
        if (declared != null && declared != size) {
            throw IOException("${target.name} is $size bytes but the upload expects $declared")
        }
        withContext(Dispatchers.IO) {
            val conn = open(target.url, target.method)
            try {
                for ((key, value) in target.headers) {
                    if (!key.equals(LENGTH, true)) conn.setRequestProperty(key, value)
                }
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(size)
                file.inputStream().use { input ->
                    conn.outputStream.use { out ->
                        val buffer = ByteArray(BUFFER)
                        var sent = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            sent += n
                            onProgress(sent)
                        }
                    }
                }
                val status = conn.responseCode
                if (status !in HTTP_OK until HTTP_MULTIPLE) {
                    throw ApiException(status, "put_failed", "Upload of ${target.name} was refused ($status)")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun post(url: String, appCheckToken: String, json: String?): Response =
        withContext(Dispatchers.IO) {
            val conn = open(url, "POST")
            try {
                conn.setRequestProperty("X-Firebase-AppCheck", appCheckToken)
                if (json != null) {
                    val bytes = json.toByteArray()
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                } else {
                    conn.setFixedLengthStreamingMode(0)
                    conn.doOutput = true
                    conn.outputStream.close()
                }
                val status = conn.responseCode
                val stream = if (status >= HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
                Response(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }

    private fun open(url: String, method: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }

    private fun failure(response: Response): ApiException {
        val code = runCatching { JSON.decodeFromString<SubmitDto>(response.body).error }.getOrNull()
        val detail = code?.let { " ($it)" }.orEmpty()
        return ApiException(response.status, code, "The server answered ${response.status}$detail")
    }

    private inline fun <reified T> decode(response: Response): T = try {
        JSON.decodeFromString<T>(response.body)
    } catch (e: IllegalArgumentException) {
        throw IOException("Unreadable server response: ${e.message}", e)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val LENGTH = "Content-Length"
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 64 * 1024
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_MULTIPLE = 300
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_BAD_GATEWAY = 502
    }
}
