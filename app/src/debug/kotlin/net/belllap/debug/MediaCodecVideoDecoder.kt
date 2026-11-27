package net.belllap.debug

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import net.belllap.vision.core.Frame
import net.belllap.vision.core.VideoDecoder
import java.io.File

/** Decodes the first video track with MediaCodec into 8-bit luma [Frame]s, keeping the container PTS. */
class MediaCodecVideoDecoder : VideoDecoder {
    override fun decode(file: File): Sequence<Frame> = sequence {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val size = extractor.readSampleData(checkNotNull(codec.getInputBuffer(i)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    if (info.size > 0) {
                        val frame = codec.getOutputImage(o)?.use { toFrame(it, info.presentationTimeUs * NS_PER_US) }
                        if (frame != null) yield(frame)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun toFrame(image: android.media.Image, ptsNs: Long): Frame {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        val plane = image.planes[0]
        val buf = plane.buffer
        val luma = ByteArray(w * h)
        for (y in 0 until h) {
            val row = (y + crop.top) * plane.rowStride + crop.left * plane.pixelStride
            for (x in 0 until w) luma[y * w + x] = buf.get(row + x * plane.pixelStride)
        }
        return Frame(ptsNs, w, h, w, luma)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val NS_PER_US = 1_000L
    }
}
