package net.belllap.bugreport

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies a window of an MP4 without re-encoding, using `MediaExtractor` and `MediaMuxer`. Only the video track is
 * kept: the report needs the picture, and dropping audio avoids sending anyone's speech. Because there is no
 * re-encode, the cut starts on the keyframe at or before the requested start, so the clip can begin a little early;
 * [trim] returns the window actually written.
 */
class MediaVideoTrimmer : VideoTrimmer {
    override suspend fun durationUs(source: File): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ms * US_PER_MS
        } finally {
            retriever.release()
        }
    }

    override suspend fun trim(source: File, window: TrimWindow, out: File): TrimWindow = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(window.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
            }
            val outTrack = muxer.addTrack(format)
            muxer.start()
            copySamples(extractor, muxer, outTrack, window)
        } finally {
            runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
        }
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, track: Int, window: TrimWindow): TrimWindow {
        val buffer = ByteBuffer.allocate(BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        val firstUs = extractor.sampleTime
        var lastUs = firstUs
        while (extractor.sampleTime in 0..window.endUs) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime - firstUs
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(track, buffer, info)
            lastUs = extractor.sampleTime
            extractor.advance()
        }
        return TrimWindow(firstUs, lastUs)
    }

    private companion object {
        const val US_PER_MS = 1000L
        const val BUFFER_BYTES = 4 * 1024 * 1024
    }
}
