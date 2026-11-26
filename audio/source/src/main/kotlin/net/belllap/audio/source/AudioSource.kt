package net.belllap.audio.source

import kotlinx.coroutines.flow.Flow

/** A block of mono 16-bit PCM. [ptsNs] is the presentation time of the first sample, never the wall clock. */
class PcmChunk(val ptsNs: Long, val sampleRate: Int, val samples: ShortArray) {
    val durationNs: Long get() = samples.size * NANOS_PER_SECOND / sampleRate

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** Where audio comes from: the microphone, a WAV file or an in-memory buffer. Detectors only see this interface. */
interface AudioSource {
    val sampleRate: Int

    /** Cold flow. Each collection starts from the beginning of the source. */
    fun chunks(): Flow<PcmChunk>
}
