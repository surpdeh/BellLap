package net.belllap.audio.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Replays an in-memory buffer in fixed-size chunks. PTS comes from the sample index. */
class PcmAudioSource(
    private val samples: ShortArray,
    override val sampleRate: Int,
    private val chunkMs: Int = DEFAULT_CHUNK_MS,
) : AudioSource {
    init {
        require(sampleRate > 0 && chunkMs > 0) { "sampleRate and chunkMs must be positive" }
    }

    override fun chunks(): Flow<PcmChunk> = flow {
        val chunkSamples = maxOf(1, sampleRate * chunkMs / MS_PER_SECOND)
        var start = 0
        while (start < samples.size) {
            val end = minOf(samples.size, start + chunkSamples)
            emit(PcmChunk(start * PcmChunk.NANOS_PER_SECOND / sampleRate, sampleRate, samples.copyOfRange(start, end)))
            start = end
        }
    }

    companion object {
        const val DEFAULT_CHUNK_MS = 10
        private const val MS_PER_SECOND = 1000
    }
}
