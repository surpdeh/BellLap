package net.belllap.core.statemachine

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Synthesizes the F15 cues as mono 16-bit PCM, so the app ships no audio assets. Pure Kotlin. */
@Suppress("MagicNumber")
object ToneSynth {
    const val SAMPLE_RATE = 44_100
    private const val PEAK = 0.9
    private const val FADE_MS = 5

    fun render(cue: Cue, sampleRate: Int = SAMPLE_RATE): ShortArray = when (cue) {
        Cue.ARMED_BEEPS -> concat(
            beep(1_000.0, 250, sampleRate),
            silence(150, sampleRate),
            beep(1_000.0, 250, sampleRate),
            silence(150, sampleRate),
            beep(1_000.0, 250, sampleRate),
        )
        Cue.CROSSING_BEEP -> beep(1_500.0, 120, sampleRate)
        Cue.STAND_DOWN_CHIME -> concat(note(880.0, 300, sampleRate), note(660.0, 300, sampleRate))
    }

    private fun samples(ms: Int, sampleRate: Int) = ms * sampleRate / 1000

    private fun silence(ms: Int, sampleRate: Int) = ShortArray(samples(ms, sampleRate))

    /** A square-faded sine: the 5 ms fades avoid clicks without softening the attack. */
    private fun beep(freq: Double, ms: Int, sampleRate: Int): ShortArray {
        val n = samples(ms, sampleRate)
        val fade = samples(FADE_MS, sampleRate)
        return ShortArray(n) { i ->
            val env = minOf(1.0, i / fade.toDouble(), (n - 1 - i) / fade.toDouble())
            pcm(sin(2 * PI * freq * i / sampleRate) * env)
        }
    }

    /** A sine with a short attack and an exponential decay, like a struck chime. */
    private fun note(freq: Double, ms: Int, sampleRate: Int): ShortArray {
        val n = samples(ms, sampleRate)
        val fade = samples(FADE_MS, sampleRate)
        return ShortArray(n) { i ->
            val env = minOf(1.0, i / fade.toDouble()) * exp(-4.0 * i / n)
            pcm(sin(2 * PI * freq * i / sampleRate) * env)
        }
    }

    private fun pcm(x: Double) = (x * PEAK * Short.MAX_VALUE).roundToInt().toShort()

    private fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
