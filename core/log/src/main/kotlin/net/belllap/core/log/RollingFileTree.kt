package net.belllap.core.log

import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Timber tree writing `belllap.log` in [logsDir], rolling over 5 files of 2 MB by default. */
class RollingFileTree(
    logsDir: File,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    maxFiles: Int = DEFAULT_MAX_FILES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Timber.Tree() {
    private val writer = RollingFileWriter(logsDir, "belllap", "log", maxBytes, maxFiles)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMs()))
        val body = if (t == null) message else message + "\n" + stackTrace(t)
        writer.appendLine("$stamp ${level(priority)}/${tag.orEmpty()}: $body")
    }

    private fun stackTrace(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        const val DEFAULT_MAX_FILES = 5
    }

    private fun level(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'A'
    }
}
