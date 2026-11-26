package net.belllap.audio.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The hardware seam. The Android module implements this over `AudioRecord`; JVM tests use a fake.
 * [read] blocks until it fills some of the buffer and returns the number of samples, or a value <= 0 at the end.
 */
interface PcmRecorder {
    val sampleRate: Int

    fun start()

    fun read(buffer: ShortArray): Int

    fun stop()
}

/** Live [AudioSource] over a [PcmRecorder]. PTS is derived from the sample count, so it is wall-clock free. */
class MicAudioSource(
    private val recorder: PcmRecorder,
    private val chunkSamples: Int = recorder.sampleRate / CHUNKS_PER_SECOND,
) : AudioSource {
    override val sampleRate: Int get() = recorder.sampleRate

    override fun chunks(): Flow<PcmChunk> = flow {
        recorder.start()
        try {
            val buffer = ShortArray(chunkSamples)
            var total = 0L
            while (true) {
                val n = recorder.read(buffer)
                if (n <= 0) break
                emit(PcmChunk(total * PcmChunk.NANOS_PER_SECOND / sampleRate, sampleRate, buffer.copyOf(n)))
                total += n
            }
        } finally {
            recorder.stop()
        }
    }

    private companion object {
        const val CHUNKS_PER_SECOND = 100
    }
}
