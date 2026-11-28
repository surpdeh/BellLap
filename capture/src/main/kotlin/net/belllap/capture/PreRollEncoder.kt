package net.belllap.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import net.belllap.core.model.EncodedSample
import net.belllap.core.model.PreRollBuffer
import net.belllap.core.model.SampleSink
import java.io.File

/**
 * The F30 pre-roll encoder. A surface-input H.264 encoder with a 1 s GOP runs for as long as the session is armed.
 * Its output sits in a [PreRollBuffer]; [startRecording] flushes the buffered seconds into an MP4 and the same
 * encoder keeps feeding it, so there is no encoder restart at the splice.
 *
 * Render camera frames into [inputSurface] with PTS in nanoseconds (the camera clock). Call [release] when done.
 */
class PreRollEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitRate: Int,
    preRollSeconds: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val inputSurface: Surface
    private val buffer = PreRollBuffer(preRollSeconds * US_PER_S)
    private val drainThread: Thread

    @Volatile private var running = true

    @Volatile private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        drainThread = Thread(::drain, "PreRollEncoder").also { it.start() }
    }

    /**
     * Starts `file` with the buffered pre-roll followed by live video. Returns the PTS (us, camera clock) of the
     * first sample written, or null when no key frame was buffered yet and the file starts at the next one.
     * The muxer starts on the first sample, so the file is never empty-headed.
     */
    fun startRecording(file: File): Long? {
        val format = checkNotNull(outputFormat) { "encoder has produced no output yet" }
        val m = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = m.addTrack(format)
        m.start()
        muxer = m
        val info = MediaCodec.BufferInfo()
        return buffer.startRecording(
            object : SampleSink {
                override fun write(sample: EncodedSample) {
                    info.set(
                        0,
                        sample.data.size,
                        sample.ptsUs,
                        if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    m.writeSampleData(track, java.nio.ByteBuffer.wrap(sample.data), info)
                }
            },
        )
    }

    fun stopRecording() {
        buffer.stopRecording()
        muxer?.let {
            it.stop()
            it.release()
        }
        muxer = null
    }

    fun release() {
        running = false
        drainThread.join(JOIN_MS)
        if (buffer.isRecording) stopRecording()
        codec.stop()
        codec.release()
        inputSurface.release()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, POLL_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                index >= 0 -> {
                    val out = checkNotNull(codec.getOutputBuffer(index))
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        out.position(info.offset).limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { out.get(it) }
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        buffer.add(EncodedSample(bytes, info.presentationTimeUs, key))
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val GOP_SECONDS = 1
        const val US_PER_S = 1_000_000L
        const val POLL_US = 10_000L
        const val JOIN_MS = 1_000L
    }
}
