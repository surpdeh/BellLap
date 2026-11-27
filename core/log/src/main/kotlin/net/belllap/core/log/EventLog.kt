package net.belllap.core.log

import kotlinx.serialization.json.Json
import java.io.File

/** JSONL event log at `<logsDir>/events.jsonl`, rotated across [maxFiles] files of at most [maxBytes]. */
class EventLog(
    logsDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    maxFiles: Int = DEFAULT_MAX_FILES,
    private val decimator: ScoreDecimator = ScoreDecimator(),
) {
    private val writer = RollingFileWriter(logsDir, "events", "jsonl", maxBytes, maxFiles)

    val files: List<File> get() = writer.allFiles()

    fun log(event: LogEvent) {
        writer.appendLine(json.encodeToString(LogEntry.serializer(), LogEntry(nowMs(), event)))
    }

    fun transition(from: String, to: String, trigger: String, ptsNs: Long? = null) =
        log(StateTransition(from, to, trigger, ptsNs))

    /** Logs the score only if the decimator accepts it. Returns whether it was written. */
    fun detectorScore(detector: String, score: Double, ptsNs: Long): Boolean {
        if (!decimator.shouldLog(detector, score, ptsNs)) return false
        log(DetectorScore(detector, score, ptsNs))
        return true
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000L
        const val DEFAULT_MAX_FILES = 5

        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = false
        }

        fun parse(line: String): LogEntry = json.decodeFromString(LogEntry.serializer(), line)
    }
}
