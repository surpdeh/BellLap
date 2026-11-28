package net.belllap.bugreport

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TraceEntry(val name: String, val file: File, val modifiedMs: Long = file.lastModified())

object TraceZip {
    /**
     * Zips [entries] into [out]. While the zip is over [maxBytes] the oldest entry is dropped, never the last one left.
     * Returns the names that made it in, newest first.
     */
    fun write(out: File, entries: List<TraceEntry>, maxBytes: Long): List<String> {
        val kept = entries.sortedByDescending { it.modifiedMs }.toMutableList()
        while (true) {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                kept.forEach { e ->
                    zip.putNextEntry(ZipEntry(e.name))
                    e.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (out.length() <= maxBytes || kept.size <= 1) return kept.map { it.name }
            kept.removeAt(kept.lastIndex)
        }
    }
}
