package net.belllap.core.log

import java.io.File

/**
 * Appends lines to `<base>.<ext>`, rotating to `<base>.1.<ext>` .. `<base>.(maxFiles-1).<ext>`
 * once the active file would exceed [maxBytes]. The oldest file is dropped. Thread-safe.
 */
class RollingFileWriter(
    private val dir: File,
    private val baseName: String,
    private val extension: String,
    private val maxBytes: Long,
    private val maxFiles: Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxFiles >= 1) { "maxFiles must be at least 1" }
    }

    private val lock = Any()
    private var size: Long = -1

    val activeFile: File get() = fileAt(0)

    fun fileAt(index: Int): File = File(dir, if (index == 0) "$baseName.$extension" else "$baseName.$index.$extension")

    fun allFiles(): List<File> = (0 until maxFiles).map(::fileAt).filter { it.exists() }

    fun appendLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            dir.mkdirs()
            if (size < 0) size = activeFile.takeIf { it.exists() }?.length() ?: 0
            if (size > 0 && size + bytes.size > maxBytes) rotate()
            activeFile.appendBytes(bytes)
            size += bytes.size
        }
    }

    private fun rotate() {
        fileAt(maxFiles - 1).delete()
        for (i in maxFiles - 2 downTo 0) {
            val f = fileAt(i)
            if (f.exists()) f.renameTo(fileAt(i + 1))
        }
        size = 0
    }
}
