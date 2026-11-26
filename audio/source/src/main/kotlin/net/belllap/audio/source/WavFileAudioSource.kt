package net.belllap.audio.source

import kotlinx.coroutines.flow.Flow
import java.io.File

/** File-backed [AudioSource]: decodes a mono 16-bit WAV and replays it as if it were live audio. */
class WavFileAudioSource(file: File, chunkMs: Int = PcmAudioSource.DEFAULT_CHUNK_MS) : AudioSource {
    private val delegate = WavIo.read(file).let { PcmAudioSource(it.samples, it.sampleRate, chunkMs) }
    override val sampleRate: Int get() = delegate.sampleRate

    override fun chunks(): Flow<PcmChunk> = delegate.chunks()
}
