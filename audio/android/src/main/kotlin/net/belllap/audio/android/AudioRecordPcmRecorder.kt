package net.belllap.audio.android

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import net.belllap.audio.source.PcmRecorder
import timber.log.Timber

/** Which `MediaRecorder.AudioSource` to capture from. Pure, so the rule is testable without a device. */
object AudioInput {
    /**
     * UNPROCESSED when the device says it is really unprocessed: no AGC or noise suppression to eat the bell's
     * tonal partials. Otherwise MIC. Never VOICE_RECOGNITION (flat only to about 4 kHz) or VOICE_COMMUNICATION
     * (noise suppression and AGC). See docs/research/technical-architecture.md §B.
     */
    fun choose(unprocessedSupported: Boolean): Int =
        if (unprocessedSupported) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

    fun name(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source $source"
    }

    fun unprocessedSupported(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED).toBoolean()
    }
}

/** 16 kHz mono 16-bit capture through `AudioRecord`: the hardware side of [net.belllap.audio.source.MicAudioSource]. */
class AudioRecordPcmRecorder(private val source: Int) : PcmRecorder {
    override val sampleRate: Int = SAMPLE_RATE
    private var record: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "16 kHz mono capture is not supported (getMinBufferSize=$min)" }
        val r = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, SAMPLE_RATE / BUFFERS_PER_SECOND * BYTES_PER_SAMPLE),
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord did not initialise for ${AudioInput.name(source)}"
        }
        r.startRecording()
        Timber.i("Bell capture started: %s, %d Hz mono", AudioInput.name(source), SAMPLE_RATE)
        record = r
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BUFFERS_PER_SECOND = 4
        private const val BYTES_PER_SAMPLE = 2
    }
}
