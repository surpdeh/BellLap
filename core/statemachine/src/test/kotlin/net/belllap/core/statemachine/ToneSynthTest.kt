package net.belllap.core.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {
    private val rate = ToneSynth.SAMPLE_RATE

    private fun ms(n: Int) = n * 1000.0 / rate

    private fun zeroCrossings(s: ShortArray, from: Int, to: Int): Int =
        (from + 1 until to).count { (s[it - 1] < 0) != (s[it] < 0) }

    @Test
    fun lengths() {
        assertEquals(1_050.0, ms(ToneSynth.render(Cue.ARMED_BEEPS).size), 1.0)
        assertEquals(120.0, ms(ToneSynth.render(Cue.CROSSING_BEEP).size), 1.0)
        assertEquals(600.0, ms(ToneSynth.render(Cue.STAND_DOWN_CHIME).size), 1.0)
    }

    @Test
    fun loudWithoutClipping() {
        for (cue in Cue.entries) {
            val s = ToneSynth.render(cue)
            val peak = s.maxOf { abs(it.toInt()) }
            assertTrue("$cue peak $peak", peak > Short.MAX_VALUE / 2 && peak <= Short.MAX_VALUE)
        }
    }

    @Test
    fun armedSeriesIsThreeBeepsWithGaps() {
        val s = ToneSynth.render(Cue.ARMED_BEEPS)
        val at = { t: Int -> t * rate / 1000 }
        assertTrue(s.copyOfRange(at(20), at(230)).any { it.toInt() != 0 })
        assertTrue(s.copyOfRange(at(260), at(390)).all { it.toInt() == 0 })
        assertTrue(s.copyOfRange(at(420), at(630)).any { it.toInt() != 0 })
        assertTrue(s.copyOfRange(at(660), at(790)).all { it.toInt() == 0 })
        assertTrue(s.copyOfRange(at(820), at(1_030)).any { it.toInt() != 0 })
    }

    @Test
    fun cuesHaveDistinctPitchAndShape() {
        val armed = ToneSynth.render(Cue.ARMED_BEEPS)
        val crossing = ToneSynth.render(Cue.CROSSING_BEEP)
        val chime = ToneSynth.render(Cue.STAND_DOWN_CHIME)
        val hz = { s: ShortArray, a: Int, b: Int -> zeroCrossings(s, a, b) / 2.0 / ((b - a) / rate.toDouble()) }
        assertEquals(1_000.0, hz(armed, 1_000, 10_000), 30.0)
        assertEquals(1_500.0, hz(crossing, 500, 4_500), 40.0)
        val half = chime.size / 2
        assertEquals(880.0, hz(chime, 500, 6_000), 30.0)
        assertEquals(660.0, hz(chime, half + 500, half + 6_000), 30.0)
        assertTrue(armed.size != crossing.size && crossing.size != chime.size)
    }

    @Test
    fun chimeDecays() {
        val c = ToneSynth.render(Cue.STAND_DOWN_CHIME)
        val peak = { a: Int, b: Int -> c.copyOfRange(a, b).maxOf { abs(it.toInt()) } }
        assertTrue(peak(c.size / 2 - 2_000, c.size / 2) < peak(0, 2_000) / 2)
    }
}
