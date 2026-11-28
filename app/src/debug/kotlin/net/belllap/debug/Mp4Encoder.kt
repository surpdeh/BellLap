package net.belllap.debug

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import net.belllap.vision.core.Frame
import java.io.File

/** Encodes luma [Frame]s to an H.264 MP4 (chroma flat grey), so generated fixtures can go through the real decoder. */
object Mp4Encoder {
    private const val TIMEOUT_US = 10_000L
    private const val BIT_RATE = 1_000_000
    private const val I_FRAME_INTERVAL_S = 1
    private const val NS_PER_US = 1_000L
    private const val GREY: Byte = 128.toByte()

    fun encode(frames: List<Frame>, fps: Int, out: File) {
        val first = frames.first()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, first.width, first.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        if (next == frames.size) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val f = frames[next++]
                            fill(checkNotNull(codec.getInputImage(i)), f)
                            codec.queueInputBuffer(i, 0, first.width * first.height * 3 / 2, f.ptsNs / NS_PER_US, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config) {
                            muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(o)), info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
    }

    /** Row-wise bulk puts: per-pixel puts made encoding race_synth take minutes on the CI emulator. */
    private fun fill(image: android.media.Image, f: Frame) {
        val y = image.planes[0]
        if (y.pixelStride == 1) {
            for (row in 0 until f.height) {
                y.buffer.position(row * y.rowStride)
                y.buffer.put(f.luma, row * f.stride, f.width)
            }
        } else {
            for (row in 0 until f.height) {
                for (col in 0 until f.width) {
                    y.buffer.put(row * y.rowStride + col * y.pixelStride, f.luma[row * f.stride + col])
                }
            }
        }
        // Chroma is flat grey, so covering the whole span of a row also fills an interleaved (NV12) partner plane
        // with the same value.
        for (p in 1..2) {
            val plane = image.planes[p]
            val grey = ByteArray((f.width / 2 - 1) * plane.pixelStride + 1) { GREY }
            for (row in 0 until f.height / 2) {
                plane.buffer.position(row * plane.rowStride)
                plane.buffer.put(grey)
            }
        }
    }
}
