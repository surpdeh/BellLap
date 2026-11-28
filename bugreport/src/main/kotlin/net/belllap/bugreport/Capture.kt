package net.belllap.bugreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.belllap.audio.source.PcmRecorder
import net.belllap.audio.source.WavIo
import java.io.File

/** Records the bell for the report. The Android implementation owns the microphone, so only one runs at a time. */
interface BellClipRecorder {
    /** Records until [maxMillis] passes or [stop] is called; writes a 16-bit mono WAV to [out], returns its ms. */
    suspend fun record(out: File, maxMillis: Long = MAX_MILLIS): Long

    fun stop()

    companion object {
        const val MAX_MILLIS = 10_000L
    }
}

/** Cuts a window out of a captured MP4. The result may start earlier than asked, at the previous keyframe. */
interface VideoTrimmer {
    suspend fun durationUs(source: File): Long

    suspend fun trim(source: File, window: TrimWindow, out: File): TrimWindow
}

/** Builds the automatic attachments (trace zip, capability dump) into [outDir]. */
fun interface TraceBundler {
    suspend fun bundle(outDir: File): List<Attachment>
}

/** [BellClipRecorder] over any [PcmRecorder]; the app passes `AudioRecordPcmRecorder`. */
class PcmBellClipRecorder(private val newRecorder: () -> PcmRecorder) : BellClipRecorder {
    @Volatile
    private var stopped = false

    override suspend fun record(out: File, maxMillis: Long): Long = withContext(Dispatchers.IO) {
        stopped = false
        val recorder = newRecorder()
        val limit = (recorder.sampleRate * maxMillis / MS_PER_SECOND).toInt()
        val samples = ShortArray(limit)
        var count = 0
        recorder.start()
        try {
            val buffer = ShortArray(maxOf(1, recorder.sampleRate / CHUNKS_PER_SECOND))
            while (count < limit && !stopped) {
                currentCoroutineContext().ensureActive()
                val n = recorder.read(buffer)
                if (n <= 0) break
                val take = minOf(n, limit - count)
                buffer.copyInto(samples, count, 0, take)
                count += take
            }
        } finally {
            recorder.stop()
        }
        WavIo.write(out, recorder.sampleRate, samples.copyOf(count))
        count * MS_PER_SECOND / recorder.sampleRate
    }

    override fun stop() {
        stopped = true
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
        const val CHUNKS_PER_SECOND = 20
    }
}
