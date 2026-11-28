package net.belllap.bugreport

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `<root>/<localId>/report.json` plus the attachment files, written by temp file and atomic rename. */
class ReportStore(private val root: File) {
    private val discarded = mutableSetOf<String>()

    fun dir(localId: String) = File(root, localId)

    fun attachmentFile(localId: String, name: String) = File(dir(localId), name)

    @Synchronized
    fun save(report: QueuedReport) {
        if (report.localId in discarded) return
        val dir = dir(report.localId).apply { mkdirs() }
        val temp = File(dir, "$FILE.tmp")
        temp.writeText(JSON.encodeToString(report))
        val target = File(dir, FILE).toPath()
        Files.move(temp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Reports in creation order. A directory whose `report.json` is unreadable is skipped, never deleted. */
    fun load(): List<QueuedReport> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
        runCatching { JSON.decodeFromString<QueuedReport>(File(dir, FILE).readText()) }.getOrNull()
    }.sortedBy { it.createdAtMs }

    /** Removes the report and stops any in-flight upload from writing it back. */
    @Synchronized
    fun discard(localId: String) {
        discarded += localId
        dir(localId).deleteRecursively()
    }

    /** Frees the media of a filed report but keeps `report.json` as its record. */
    @Synchronized
    fun deleteAttachments(localId: String) {
        dir(localId).listFiles { f -> f.name != FILE }.orEmpty().forEach { it.delete() }
    }

    fun isDiscarded(localId: String): Boolean = synchronized(this) { localId in discarded }

    companion object {
        const val FILE = "report.json"
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
