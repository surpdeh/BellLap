package net.belllap.testing.fakebackend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.belllap.bugreport.AttachmentKind
import net.belllap.bugreport.Caps
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class Endpoint { CREATE, PUT, SUBMIT }

class FakeIssue(val number: Int, val url: String, val description: String, val attachmentNames: List<String>)

/**
 * An in-process fake of the F23 Worker (`backend/README.md`, "API contract"), for JVM and on-device tests. It is built
 * on plain sockets because `com.sun.net.httpserver` does not exist on Android. Upload URLs point back at this
 * enforce the exact `Content-Length` and `Content-Type` the create call declared, as R2 does.
 */
@Suppress("TooManyFunctions") // one fake server: scripting API, HTTP plumbing and the four routes belong together
class FakeBackend(private val nowMs: () -> Long = System::currentTimeMillis) : Closeable {
    private class Spec(val name: String, val kind: String, val contentType: String, val size: Long)

    private class ReportRecord(val id: String, val description: String, val specs: List<Spec>, val expiresAtMs: Long) {
        val stored = ConcurrentHashMap<String, Long>()
        var issue: FakeIssue? = null
    }

    private class Scripted(val status: Int, val body: String, val dropAfterBytes: Long?)

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "fake-backend").apply { isDaemon = true } }
    private val reports = ConcurrentHashMap<String, ReportRecord>()
    private val scripts = Endpoint.entries.associateWith { ConcurrentLinkedQueue<Scripted>() }
    private val requestLog = CopyOnWriteArrayList<String>()

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** When false the server hangs up on every request, like a phone with no network. */
    @Volatile
    var online: Boolean = true

    @Volatile
    var appCheckToken: String = VALID_TOKEN

    @Volatile
    var urlTtlMs: Long = DEFAULT_TTL_MS

    /** `METHOD path` of every request received, in order. */
    val requests: List<String> get() = requestLog.toList()

    val issues: List<FakeIssue> get() = reports.values.mapNotNull { it.issue }.sortedBy { it.number }

    /** Names of the objects held for [reportId], with their byte counts. */
    fun stored(reportId: String): Map<String, Long> = reports[reportId]?.stored?.toMap().orEmpty()

    fun reportIds(): List<String> = reports.keys.toList()

    /** Answers the next call to [endpoint] with [status] and [json] instead of the normal behaviour. */
    fun failNext(endpoint: Endpoint, status: Int, json: String = "{}") {
        scripts.getValue(endpoint).add(Scripted(status, json, null))
    }

    /** Hangs up after [bytes] bytes of the next upload body, a connection lost mid-upload. */
    fun dropNextPutAfter(bytes: Long) {
        scripts.getValue(Endpoint.PUT).add(Scripted(0, "", bytes))
    }

    /** Forgets an uploaded object, as the Worker does when it deletes a file with the wrong size. */
    fun forget(reportId: String, name: String) {
        reports[reportId]?.stored?.remove(name)
    }

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { socket.use { handle(it) } }
            }
        }
    }

    override fun close() {
        server.close()
        pool.shutdownNow()
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val input: InputStream,
    )

    @Suppress("ReturnCount") // guard clauses mirror the Worker's early-exit checks
    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream().buffered()
        val request = runCatching { readRequest(input) }.getOrNull() ?: return
        requestLog += "${request.method} ${request.path.substringBefore('?')}"
        if (!online) return
        val response = runCatching { route(request) }.getOrNull() ?: return
        val body = response.second.toByteArray()
        val head = "HTTP/1.1 ${response.first} ${reason(response.first)}\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    private fun readRequest(input: InputStream): Request {
        val line = readLine(input) ?: error("empty request")
        val parts = line.split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = readLine(input)
            if (h.isNullOrEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        return Request(parts[0], parts[1], headers, input)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun route(r: Request): Pair<Int, String>? {
        val path = r.path.substringBefore('?')
        return when {
            r.method == "POST" && path == "/v1/reports" -> guarded(r, Endpoint.CREATE, ::create)
            r.method == "POST" && path.matches(SUBMIT_PATH) ->
                guarded(r, Endpoint.SUBMIT) { _ -> submit(path.split('/')[3]) }
            r.method == "PUT" && path.startsWith("/upload/") -> put(r, path)
            else -> failure(NOT_FOUND, "not_found")
        }
    }

    @Suppress("ReturnCount") // guard clauses mirror the Worker's early-exit checks
    private fun guarded(r: Request, endpoint: Endpoint, block: (ByteArray) -> Pair<Int, String>): Pair<Int, String> {
        val length = r.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length).also { r.input.readNBytes(it, 0, length) }
        scripts.getValue(endpoint).poll()?.let { return it.status to it.body }
        if (r.headers["x-firebase-appcheck"] != appCheckToken) return failure(UNAUTHORIZED, "integrity_failed")
        return block(body)
    }

    private fun create(body: ByteArray): Pair<Int, String> {
        val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
        val description = json["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val specs = json["attachments"]?.jsonArray.orEmpty().map(::toSpec)
        val problem = problem(description, specs)
        if (problem != null) return failure(BAD_REQUEST, "invalid_request", problem)
        val id = UUID.randomUUID().toString()
        val expires = nowMs() + urlTtlMs
        reports[id] = ReportRecord(id, description, specs, expires)
        val body = buildJsonObject {
            put("reportId", id)
            putJsonArray("uploads") {
                specs.forEach { s ->
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("method", "PUT")
                            put("url", "$baseUrl/upload/$id/${s.name}?X-Amz-Signature=fake")
                            putJsonObject("headers") {
                                put("Content-Type", s.contentType)
                                put("Content-Length", s.size.toString())
                            }
                            put("expiresAt", Instant.ofEpochMilli(expires).toString())
                        },
                    )
                }
            }
        }
        return HTTP_CREATED to body.toString()
    }

    private fun toSpec(e: JsonElement): Spec {
        val o = e.jsonObject
        return Spec(
            o.getValue("name").jsonPrimitive.content,
            o.getValue("kind").jsonPrimitive.content,
            o.getValue("contentType").jsonPrimitive.content,
            o.getValue("size").jsonPrimitive.longOrNull ?: 0,
        )
    }

    private fun problem(description: String, specs: List<Spec>): String? {
        val kinds = specs.map { s -> AttachmentKind.entries.firstOrNull { it.wire == s.kind } }
        return when {
            description.isBlank() || description.length > Caps.MAX_DESCRIPTION -> "description"
            specs.size > Caps.MAX_ATTACHMENTS || specs.map { it.name }.toSet().size != specs.size -> "attachments"
            kinds.any { it == null } -> "kind"
            specs.sumOf { it.size } > Caps.MAX_TOTAL_BYTES -> "total size"
            specs.indices.any { i ->
                val caps = Caps.of(kinds[i]!!)
                specs[i].size !in 1..caps.maxBytes || specs[i].contentType !in caps.contentTypes
            } -> "attachment caps"
            else -> null
        }
    }

    @Suppress("ReturnCount") // guard clauses mirror the Worker's early-exit checks
    private fun put(r: Request, path: String): Pair<Int, String>? {
        val parts = path.split('/')
        val record = reports[parts.getOrNull(2)]
        val spec = record?.specs?.firstOrNull { it.name == parts.getOrNull(3) }
        val script = scripts.getValue(Endpoint.PUT).poll()
        val length = r.headers["content-length"]?.toLongOrNull()
        val denied = record == null || spec == null || nowMs() >= record.expiresAtMs ||
            length != spec.size || r.headers["content-type"] != spec.contentType
        if (script != null && script.dropAfterBytes == null) return script.status to script.body
        if (denied) return failure(FORBIDDEN, "forbidden")
        val buffer = ByteArray(BUFFER)
        var read = 0L
        while (read < spec.size) {
            val n = r.input.read(buffer, 0, minOf(buffer.size.toLong(), spec.size - read).toInt())
            if (n < 0) return null
            read += n
            if (script?.dropAfterBytes != null && read >= script.dropAfterBytes) return null
        }
        record.stored[spec.name] = read
        return HTTP_OK to "{}"
    }

    @Suppress("ReturnCount") // guard clauses mirror the Worker's early-exit checks
    private fun submit(reportId: String): Pair<Int, String> {
        val record = reports[reportId] ?: return failure(NOT_FOUND, "not_found")
        record.issue?.let { return HTTP_OK to issueJson(it) }
        val missing = record.specs.filter { record.stored[it.name] != it.size }.map { it.name }
        if (missing.isNotEmpty()) {
            val body = buildJsonObject {
                put("error", "uploads_missing")
                put("missing", JsonArray(missing.map(::JsonPrimitive)))
            }
            return CONFLICT to body.toString()
        }
        val number = reports.values.count { it.issue != null } + 1
        val url = "https://github.com/example/belllap/issues/$number"
        val issue = FakeIssue(number, url, record.description, record.specs.map { it.name })
        record.issue = issue
        return HTTP_CREATED to issueJson(issue)
    }

    private fun issueJson(i: FakeIssue) = buildJsonObject {
        putJsonObject("issue") {
            put("number", i.number)
            put("url", i.url)
        }
    }.toString()

    private fun failure(status: Int, code: String, detail: String? = null): Pair<Int, String> =
        status to buildJsonObject {
            put("error", code)
            detail?.let { put("detail", it) }
        }.toString()

    private fun reason(status: Int) = when (status) {
        HTTP_OK -> "OK"
        HTTP_CREATED -> "Created"
        else -> "Status"
    }

    companion object {
        const val VALID_TOKEN = "fake-app-check-token"
        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val DEFAULT_TTL_MS = 15 * 60 * 1000L
        private const val BUFFER = 64 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private val SUBMIT_PATH = Regex("/v1/reports/[^/]+/submit")
    }
}
