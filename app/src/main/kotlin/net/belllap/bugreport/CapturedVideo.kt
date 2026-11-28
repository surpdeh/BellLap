package net.belllap.bugreport

import net.belllap.core.model.Sidecar
import timber.log.Timber
import java.io.File

/** A recorded acquisition that can be attached: its MP4 and the sidecar it was written with. */
data class CapturedVideo(val name: String, val file: File, val sidecar: Sidecar) {
    val sizeBytes: Long get() = file.length()
    val candidates: List<TrimCandidate> get() = trimCandidates(sidecar)
}

/** Acquisitions in [dir] (`acq_<id>.json` + `acq_<id>.mp4`), newest first. A sidecar that does not parse is skipped. */
fun listCapturedVideos(dir: File): List<CapturedVideo> =
    dir.listFiles { f -> f.name.startsWith("acq_") && f.extension == "json" }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { json ->
            runCatching { Sidecar.decode(json.readText()) }
                .onFailure { Timber.w(it, "Skipping unreadable sidecar %s", json.name) }
                .getOrNull()
                ?.let { sc -> File(dir, sc.videoFile).takeIf { it.isFile }?.let { CapturedVideo(sc.name, it, sc) } }
        }
