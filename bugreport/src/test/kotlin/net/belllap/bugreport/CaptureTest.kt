package net.belllap.bugreport

import kotlinx.coroutines.test.runTest
import net.belllap.audio.source.PcmRecorder
import net.belllap.audio.source.WavIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class CaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecorder(override val sampleRate: Int = 16_000, private val stopAfterReads: Int = Int.MAX_VALUE) :
        PcmRecorder {
        var started = false
        var stopped = false
        var reads = 0
        var onRead: () -> Unit = {}

        override fun start() {
            started = true
        }

        override fun read(buffer: ShortArray): Int {
            reads++
            onRead()
            buffer.fill(7)
            return if (reads > stopAfterReads) 0 else buffer.size
        }

        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun bellClipIsCappedAtTenSeconds() = runTest {
        val fake = FakeRecorder()
        val out = tmp.newFile("bell.wav")

        val ms = PcmBellClipRecorder { fake }.record(out)

        assertEquals(10_000L, ms)
        val wav = WavIo.read(out)
        assertEquals(160_000, wav.samples.size)
        assertEquals(16_000, wav.sampleRate)
        assertTrue(fake.stopped)
        assertTrue(out.length() < 10 * 1024 * 1024)
    }

    @Test
    fun bellClipStopsEarlyWhenAsked() = runTest {
        val fake = FakeRecorder()
        val recorder = PcmBellClipRecorder { fake }
        fake.onRead = { if (fake.reads == 40) recorder.stop() }
        val out = tmp.newFile("bell.wav")

        val ms = recorder.record(out)

        assertEquals(2_000L, ms)
        assertEquals(32_000, WavIo.read(out).samples.size)
    }

    @Test
    fun traceZipDropsTheOldestEntriesWhenOverTheCap() {
        val noise = java.util.Random(1)

        fun entry(name: String, age: Long) = TraceEntry(
            name,
            tmp.newFile(name).apply { writeBytes(ByteArray(4000).also(noise::nextBytes)) },
            modifiedMs = 1_000_000 - age,
        )
        val entries = listOf(entry("old.log", 300), entry("mid.log", 200), entry("new.log", 100))
        val out = tmp.newFile("trace.zip")

        val kept = TraceZip.write(out, entries, maxBytes = 9_000)

        assertEquals(listOf("new.log", "mid.log"), kept)
        ZipFile(out).use {
            assertEquals(
                setOf("new.log", "mid.log"),
                it.entries().asSequence().map {
                        e ->
                    e.name
                }.toSet(),
            )
        }
        assertTrue(out.length() <= 9_000)
    }

    @Test
    fun traceZipKeepsEverythingWhenItFits() {
        val a = TraceEntry("events.jsonl", tmp.newFile("events.jsonl").apply { writeText("{}\n") }, 2)
        val b = TraceEntry("belllap.log", tmp.newFile("belllap.log").apply { writeText("hi\n") }, 1)
        val out = tmp.newFile("t.zip")

        assertEquals(listOf("events.jsonl", "belllap.log"), TraceZip.write(out, listOf(b, a), 10_000))
    }
}
