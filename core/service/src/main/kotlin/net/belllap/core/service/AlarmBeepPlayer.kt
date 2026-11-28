package net.belllap.core.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import net.belllap.core.statemachine.BeepSink
import net.belllap.core.statemachine.Cue
import net.belllap.core.statemachine.StreamVolume
import net.belllap.core.statemachine.ToneSynth
import net.belllap.core.statemachine.VolumeGuard
import java.util.concurrent.Executors

/** [StreamVolume] over one AudioManager stream. Setting the level may throw SecurityException under Do Not Disturb. */
class AudioManagerStreamVolume(private val audio: AudioManager, private val stream: Int) : StreamVolume {
    override val max: Int get() = audio.getStreamMaxVolume(stream)
    override var current: Int
        get() = audio.getStreamVolume(stream)
        set(value) = audio.setStreamVolume(stream, value, 0)
}

/**
 * Plays the F15 cues on the alarm stream (F15): synthesized PCM through a static AudioTrack, with the alarm volume
 * raised to [volumePercent] (maximum by default) for the duration and restored afterwards. [play] returns at once;
 * the tone is written and played on a single worker thread, so cues play in order.
 */
class AlarmBeepPlayer(
    context: Context,
    private val volumePercent: () -> Int = { VolumeGuard.FULL_PERCENT },
) : BeepSink {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val guard = VolumeGuard(AudioManagerStreamVolume(audio, AudioManager.STREAM_ALARM))
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "belllap-beeps").apply { isDaemon = true } }

    override fun play(cue: Cue) {
        worker.execute { playBlocking(cue) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun playBlocking(cue: Cue) {
        try {
            val pcm = ToneSynth.render(cue)
            guard.withVolume(volumePercent()) { playPcm(pcm) }
        } catch (e: SecurityException) {
            // Do Not Disturb (notification policy) can refuse a volume change; play at the current level instead.
            Log.w(TAG, "Alarm volume change refused for $cue", e)
            runCatching { playPcm(ToneSynth.render(cue)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $cue", e)
        }
    }

    private fun playPcm(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = pcm.size * MS_PER_S / ToneSynth.SAMPLE_RATE
            Thread.sleep(durationMs + TAIL_MS)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val TAG = "AlarmBeepPlayer"
        const val MS_PER_S = 1000L
        const val TAIL_MS = 100L
    }
}
