package net.belllap.audio.source

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AudioSourceTest {
    private val samples = ShortArray(1000) { (it * 7 - 3000).toShort() }

    @Test
    fun wavRoundTripIsLossless() {
        val file = File(createTempDirectory().toFile(), "a.wav")
        WavIo.write(file, 8000, samples)
        val back = WavIo.read(file)
        assertEquals(8000, back.sampleRate)
        assertArrayEquals(samples, back.samples)
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageIsRejected() {
        WavIo.parse(ByteArray(100))
    }

    @Test
    fun chunksCarryPtsFromTheSampleIndexAndCoverEverySample() {
        val chunks = runBlocking { PcmAudioSource(samples, 1000, chunkMs = 100).chunks().toList() }
        assertEquals(10, chunks.size)
        assertEquals(listOf(0L, 100_000_000L, 200_000_000L), chunks.take(3).map { it.ptsNs })
        assertArrayEquals(samples, chunks.flatMap { it.samples.toList() }.toShortArray())
    }

    @Test
    fun wavFileSourceReplaysTheFileAndIsRepeatable() {
        val file = File(createTempDirectory().toFile(), "a.wav")
        WavIo.write(file, 1000, samples)
        val source = WavFileAudioSource(file, chunkMs = 250)
        val first = runBlocking { source.chunks().toList() }
        val second = runBlocking { source.chunks().toList() }
        assertEquals(4, first.size)
        assertEquals(first.map { it.ptsNs }, second.map { it.ptsNs })
    }

    @Test
    fun micSourceStartsStopsAndTimestampsBySampleCount() {
        val recorder = FakeRecorder(listOf(100, 100, 50))
        val chunks = runBlocking { MicAudioSource(recorder, chunkSamples = 100).chunks().toList() }
        assertEquals(listOf(0L, 100_000_000L, 200_000_000L), chunks.map { it.ptsNs })
        assertEquals(50, chunks.last().samples.size)
        assertTrue(recorder.started && recorder.stopped)
    }

    private class FakeRecorder(private val reads: List<Int>) : PcmRecorder {
        override val sampleRate = 1000
        var started = false
        var stopped = false
        private var i = 0

        override fun start() {
            started = true
        }

        override fun read(buffer: ShortArray) = if (i < reads.size) reads[i++] else 0

        override fun stop() {
            stopped = true
        }
    }
}
