package net.belllap.core.model

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A recorded acquisition: its decoded sidecar and its MP4. */
data class Acquisition(val sidecar: Sidecar, val video: File)

/**
 * The acquisitions in [dir] (`acq_<id>.mp4` plus `acq_<id>.json`), as written by F17 (F19). File-backed, pure JVM.
 * A sidecar that does not decode, or whose MP4 is missing, is left out of [list] and [load]; nothing throws for it.
 */
class AcquisitionStore(private val dir: File) {
    /** Newest first, by the sidecar's modification time. */
    fun list(): List<Acquisition> =
        dir.listFiles { f -> f.name.startsWith("acq_") && f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { read(it) }

    fun load(id: String): Acquisition? = if (validId(id)) read(json(id)) else null

    /** Renames [id]; false when it is unknown, unreadable or [name] is blank. */
    fun rename(id: String, name: String): Boolean {
        val acquisition = load(id)?.takeIf { name.isNotBlank() } ?: return false
        save(acquisition.sidecar.copy(name = name.trim()))
        return true
    }

    /** Deletes both files; false when there was nothing to delete. */
    fun delete(id: String): Boolean {
        if (!validId(id)) return false
        val removedJson = json(id).delete()
        val removedVideo = video(id).delete()
        return removedJson || removedVideo
    }

    /** Writes the sidecar to a temp file in [dir], then renames it over `acq_<id>.json`. */
    fun save(sidecar: Sidecar) {
        require(validId(sidecar.id)) { "Unsafe acquisition id" }
        val temp = File(dir, "acq_${sidecar.id}.json.tmp")
        temp.writeText(sidecar.encode())
        Files.move(
            temp.toPath(),
            json(sidecar.id).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun json(id: String) = File(dir, "acq_$id.json")

    private fun video(id: String) = File(dir, "acq_$id.mp4")

    private fun validId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun read(file: File): Acquisition? {
        val sidecar = try {
            Sidecar.decode(file.readText())
        } catch (_: SidecarFormatException) {
            null
        } catch (_: java.io.IOException) {
            null
        } ?: return null
        val video = File(dir, sidecar.videoFile)
        return if (validId(sidecar.id) && video.isFile) Acquisition(sidecar, video) else null
    }
}
