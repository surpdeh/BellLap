package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollBufferTest {
    private val frameUs = 33_333L
    private val gop = 30 // 1 s GOP at 30 fps

    private class Collect : SampleSink {
        val out = mutableListOf<EncodedSample>()

        override fun write(sample: EncodedSample) {
            out += sample
        }
    }

    private fun sample(i: Int) = EncodedSample(byteArrayOf(i.toByte()), i * frameUs, i % gop == 0)

    private fun feed(b: PreRollBuffer, range: IntRange) = range.forEach { b.add(sample(it)) }

    @Test
    fun heldPreRollStartsOnAKeyFrameAndCoversTheWindow() {
        val b = PreRollBuffer(windowUs = 3_000_000)
        feed(b, 0..299) // 10 s
        val sink = Collect()
        val first = b.startRecording(sink)
        assertTrue(sink.out.first().keyFrame)
        assertEquals(0L, sink.out.first().ptsUs)
        val spanUs = sink.out.last().ptsUs
        assertTrue("span $spanUs", spanUs >= 3_000_000 - frameUs)
        assertTrue("span $spanUs", spanUs <= 3_000_000 + 1_000_000)
        assertEquals(0, (first!! / frameUs) % gop)
    }

    @Test
    fun spliceHasNoGapNoRepeatAndStrictlyRisingPts() {
        val b = PreRollBuffer(windowUs = 2_000_000)
        feed(b, 0..119)
        val sink = Collect()
        b.startRecording(sink)
        feed(b, 120..239)
        val firstIndex = sink.out.first().data[0].toInt() and 0xff
        // Payload byte i identifies the source frame; consecutive indices prove no loss or duplicate.
        val ids = sink.out.map { it.data[0].toInt() and 0xff }
        assertEquals((firstIndex..239).map { it and 0xff }, ids)
        sink.out.zipWithNext().forEach { (a, c) -> assertEquals(frameUs, c.ptsUs - a.ptsUs) }
        assertEquals(0, b.droppedOutOfOrder)
    }

    @Test
    fun samplesBeforeTheFirstKeyFrameAreNotBuffered() {
        val b = PreRollBuffer(1_000_000)
        feed(b, 1..10)
        assertEquals(0, b.size)
    }

    @Test
    fun recordingWithAnEmptyBufferBeginsAtTheNextKeyFrame() {
        val b = PreRollBuffer(1_000_000)
        val sink = Collect()
        assertEquals(null, b.startRecording(sink))
        feed(b, 5..35)
        assertTrue(sink.out.first().keyFrame)
        assertEquals(0L, sink.out.first().ptsUs)
    }

    @Test
    fun nonAdvancingPtsIsDroppedAndCounted() {
        val b = PreRollBuffer(1_000_000)
        val sink = Collect()
        feed(b, 0..1)
        b.startRecording(sink)
        b.add(sample(1))
        b.add(sample(2))
        assertEquals(1, b.droppedOutOfOrder)
        assertEquals(listOf(0L, frameUs, 2 * frameUs), sink.out.map { it.ptsUs })
    }

    @Test
    fun afterStopTheBufferFillsAgain() {
        val b = PreRollBuffer(1_000_000)
        feed(b, 0..29)
        b.startRecording(Collect())
        b.stopRecording()
        feed(b, 30..89)
        assertTrue(b.size > 0)
        assertTrue(!b.isRecording)
    }
}
