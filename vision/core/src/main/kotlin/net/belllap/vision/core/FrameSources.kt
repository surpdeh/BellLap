package net.belllap.vision.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Replays frames already in memory. PTS is whatever the frames carry. */
class InMemoryFrameSource(private val frames: List<Frame>) : FrameSource {
    override fun frames(): Flow<Frame> = flow { frames.forEach { emit(it) } }
}

/** File-backed source: PNG files in name order, with PTS = index / [fps]. */
class ImageSequenceFrameSource(private val files: List<File>, private val fps: Double) : FrameSource {
    init {
        require(fps > 0) { "fps must be positive" }
    }

    override fun frames(): Flow<Frame> = flow {
        files.forEachIndexed { i, file -> emit(LumaImages.readPng(file, ptsOf(i, fps))) }
    }

    companion object {
        fun ptsOf(index: Int, fps: Double): Long = Math.round(index * NANOS_PER_SECOND / fps)

        private const val NANOS_PER_SECOND = 1e9

        fun fromDirectory(dir: File, fps: Double): ImageSequenceFrameSource {
            val files = dir.listFiles {
                    f ->
                f.extension.equals("png", ignoreCase = true)
            }.orEmpty().sortedBy { it.name }
            require(files.isNotEmpty()) { "no PNG frames in $dir" }
            return ImageSequenceFrameSource(files, fps)
        }
    }
}

/**
 * The seam for video decoding. The Android implementation wraps MediaExtractor and MediaCodec and keeps the
 * container PTS; it arrives with Replay mode (F24). JVM tests use image sequences instead.
 */
interface VideoDecoder {
    fun decode(file: File): Sequence<Frame>
}

/** File-backed source over a video file, decoded by a platform [VideoDecoder]. */
class VideoFileFrameSource(private val file: File, private val decoder: VideoDecoder) : FrameSource {
    override fun frames(): Flow<Frame> = flow { decoder.decode(file).forEach { emit(it) } }
}
