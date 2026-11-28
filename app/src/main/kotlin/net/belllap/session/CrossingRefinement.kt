package net.belllap.session

import net.belllap.core.model.CrossingSource
import net.belllap.core.model.SavedAcquisition
import net.belllap.core.model.Sidecar
import net.belllap.vision.core.VideoDecoder
import net.belllap.vision.finishline.CrossingRefiner
import net.belllap.vision.finishline.toSidecar
import java.io.File

/**
 * The post-process pass of F18: decodes the saved MP4 with [decoder], runs [CrossingRefiner] and rewrites the sidecar
 * with the refined crossings (see [Sidecar.withRefinedCrossings]). Blocking: call it off the main thread. Frames
 * stream through the refiner one at a time, so memory stays at the strips, not the video.
 */
class CrossingRefinement(private val decoder: VideoDecoder) {
    /**
     * Returns the rewritten sidecar, or null when nothing was changed: no finish line in the sidecar, or a file
     * that decoded to no frames. Decoder and file errors propagate and leave the sidecar as it was.
     */
    fun refine(saved: SavedAcquisition): Sidecar? {
        val sidecar = Sidecar.decode(saved.sidecar.readText())
        val line = sidecar.line ?: return null
        var firstPtsNs: Long? = null
        val frames = decoder.decode(saved.video).onEach { if (firstPtsNs == null) firstPtsNs = it.ptsNs }
        val found = CrossingRefiner.refine(frames.asIterable(), line)
        return firstPtsNs?.let { first ->
            // The MP4 clock starts near zero; the sidecar uses the camera clock, which began at firstFramePtsUs.
            val offsetNs = sidecar.firstFramePtsUs * NS_PER_US - first
            val refined = found.map {
                it.toSidecar(sidecar.firstFramePtsUs, sidecar.fps, CrossingSource.REFINED, offsetNs)
            }
            sidecar.withRefinedCrossings(refined).also { write(saved.sidecar, it) }
        }
    }

    private fun write(target: File, sidecar: Sidecar) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(sidecar.encode())
        check(tmp.renameTo(target)) { "could not replace ${target.name}" }
    }

    private companion object {
        const val NS_PER_US = 1000L
    }
}
