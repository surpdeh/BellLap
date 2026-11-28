package net.belllap.bugreport

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The automatic attachments: one `trace-<time>.zip` of the event logs, the Timber logs, the acquisition sidecars
 * (never the MP4s) and this process's logcat, plus `capabilities.json` from the device probe.
 */
class AndroidTraceBundler(
    private val logsDir: File,
    private val acquisitionsDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val logcat: () -> String? = ::readLogcat,
) : TraceBundler {
    override suspend fun bundle(outDir: File): List<Attachment> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val logs = logsDir.listFiles { f -> f.isFile && (f.name.startsWith("events") || f.extension == "log") }
        val sidecars = acquisitionsDir.listFiles { f -> f.name.startsWith("acq_") && f.extension == "json" }
        val logcatFile = logcat()?.takeIf { it.isNotBlank() }?.let {
            File(
                outDir,
                "logcat.txt",
            ).apply { writeText(it) }
        }
        val entries = (logs.orEmpty().toList() + sidecars.orEmpty().toList()).map { TraceEntry(it.name, it) } +
            listOfNotNull(logcatFile?.let { TraceEntry(it.name, it, nowMs()) })
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs()))
        val zip = File(outDir, "trace-$stamp.zip")
        val kept = TraceZip.write(zip, entries, Caps.of(AttachmentKind.TRACE).maxBytes)
        Timber.i("Bug report trace bundle: %d of %d files, %d bytes", kept.size, entries.size, zip.length())
        logcatFile?.delete()
        listOfNotNull(
            Attachment(zip.name, AttachmentKind.TRACE, "application/zip", zip).takeIf { kept.isNotEmpty() },
            capabilities(outDir),
        )
    }

    private fun capabilities(outDir: File): Attachment? {
        val source = File(logsDir, CAPABILITIES_SOURCE).takeIf { it.isFile } ?: return null
        val copy = source.copyTo(File(outDir, CAPABILITIES_NAME), overwrite = true)
        return Attachment(CAPABILITIES_NAME, AttachmentKind.CAPABILITIES, "application/json", copy)
    }

    private companion object {
        const val CAPABILITIES_NAME = "capabilities.json"
        const val CAPABILITIES_SOURCE = "device-capabilities.json"
    }
}

/** This process's log buffer, which an app may read without a permission. Null when the device refuses. */
private fun readLogcat(): String? = runCatching {
    val process = ProcessBuilder("logcat", "-d", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
    process.inputStream.bufferedReader().use { it.readText() }.also { process.destroy() }
}.onFailure { Timber.w(it, "logcat could not be read") }.getOrNull()
