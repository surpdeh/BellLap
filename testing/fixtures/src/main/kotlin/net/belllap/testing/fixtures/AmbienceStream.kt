package net.belllap.testing.fixtures

import net.belllap.audio.source.PcmChunk
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hours of race-side ambience with no bell in it, generated on the fly so it never touches the disk: pink noise
 * plus a distractor every 2 to 8 s. Distractors are the sounds most likely to fool a bell gate: whistles, claps,
 * car horns, brake squeal (tonal, high, sustained), freewheel ticking, voice-band babble and a PA-style beep.
 * Used to measure false alarms per hour; a 30 s fixture cannot resolve 0.2 per hour.
 */
class AmbienceStream(seed: Long, private val sampleRate: Int = BellAudioGenerator.DEFAULT_RATE) {
    private val rng = Random(seed)
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0
    private var position = 0L
    private var nextEvent = gapSamples()
    private val active = ArrayList<Pair<Long, DoubleArray>>()

    /** Distractor counts by kind, for the report. */
    val distractorCounts = sortedMapOf<String, Int>()

    /** The next [n] samples as a chunk; PTS continues from the previous chunk. */
    fun next(n: Int): PcmChunk {
        val out = ShortArray(n)
        val pts = position * NS_PER_S / sampleRate
        for (i in 0 until n) {
            if (position == nextEvent) {
                active += position to distractor()
                nextEvent = position + gapSamples()
            }
            var v = pink() * NOISE_RMS
            val it = active.iterator()
            while (it.hasNext()) {
                val (start, wave) = it.next()
                val k = (position - start).toInt()
                if (k >= wave.size) it.remove() else v += wave[k]
            }
            out[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            position++
        }
        return PcmChunk(pts, sampleRate, out)
    }

    private fun gapSamples() = (rng.nextDouble(MIN_GAP_S, MAX_GAP_S) * sampleRate).toLong()

    private fun distractor(): DoubleArray {
        val kind = KINDS[rng.nextInt(KINDS.size)]
        distractorCounts[kind] = (distractorCounts[kind] ?: 0) + 1
        val amp = NOISE_RMS * Math.pow(10.0, rng.nextDouble(MIN_LEVEL_DB, MAX_LEVEL_DB) / 20) * sqrt(2.0)
        return when (kind) {
            "whistle" -> tone(rng.nextDouble(2500.0, 3500.0), rng.nextDouble(0.3, 1.2), amp, vibratoHz = 5.0)
            "horn" -> tone(rng.nextDouble(350.0, 500.0), rng.nextDouble(0.4, 1.5), amp, vibratoHz = 0.0)
            "squeal" -> squeal(amp)
            "beep" -> tone(rng.nextDouble(800.0, 3000.0), rng.nextDouble(0.15, 0.5), amp, vibratoHz = 0.0)
            "freewheel" -> freewheel(amp)
            "voice" -> voice(amp)
            else -> clap(amp)
        }
    }

    private fun tone(hz: Double, seconds: Double, amp: Double, vibratoHz: Double): DoubleArray {
        val n = (seconds * sampleRate).toInt()
        return DoubleArray(n) {
            val t = it.toDouble() / sampleRate
            val fade = minOf(1.0, t / FADE_S, (seconds - t) / FADE_S)
            amp * fade * sin(2 * PI * hz * t + if (vibratoHz > 0) VIBRATO * sin(2 * PI * vibratoHz * t) else 0.0)
        }
    }

    /** Brake squeal: a high tone that swells in, wavers in pitch and holds, often with a second partial. */
    private fun squeal(amp: Double): DoubleArray {
        val hz = rng.nextDouble(3000.0, 6500.0)
        val seconds = rng.nextDouble(0.6, 2.0)
        val n = (seconds * sampleRate).toInt()
        return DoubleArray(n) {
            val t = it.toDouble() / sampleRate
            val env = minOf(1.0, t / SQUEAL_ATTACK_S, (seconds - t) / FADE_S)
            val phase = 2 * PI * hz * t + SQUEAL_WOBBLE * sin(2 * PI * 3 * t)
            amp * env * (sin(phase) + 0.4 * sin(2 * phase))
        }
    }

    private fun freewheel(amp: Double): DoubleArray {
        val rate = rng.nextDouble(8.0, 16.0)
        val n = (rng.nextDouble(1.0, 3.0) * sampleRate).toInt()
        val out = DoubleArray(n)
        var at = 0.0
        while (at < n) {
            val start = at.toInt()
            for (i in 0 until minOf(CLICK_SAMPLES, n - start)) {
                out[start + i] += 2 * amp * fastGaussian() * exp(-i / CLICK_TAU_SAMPLES)
            }
            at += sampleRate / rate
        }
        return out
    }

    /** Babble: noise through two moving formant-like resonators, amplitude-modulated at a syllable rate. */
    private fun voice(amp: Double): DoubleArray {
        val n = (rng.nextDouble(1.0, 3.0) * sampleRate).toInt()
        val f1 = rng.nextDouble(400.0, 900.0)
        val f2 = rng.nextDouble(1100.0, 2600.0)
        val r1 = Resonator(f1, sampleRate)
        val r2 = Resonator(f2, sampleRate)
        return DoubleArray(n) {
            val t = it.toDouble() / sampleRate
            val syllable = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)
            val x = fastGaussian()
            amp * syllable * (r1.step(x) + 0.5 * r2.step(x)) * VOICE_GAIN
        }
    }

    private fun clap(amp: Double): DoubleArray {
        val n = (CLAP_S * sampleRate).toInt()
        return DoubleArray(n) { 2 * amp * fastGaussian() * exp(-it.toDouble() / sampleRate / CLAP_TAU_S) }
    }

    private class Resonator(hz: Double, rate: Int) {
        private val r = 0.97
        private val c = 2 * r * Math.cos(2 * PI * hz / rate)
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(x: Double): Double {
            val y = x * (1 - r) + c * y1 - r * r * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    private fun fastGaussian(): Double =
        (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 2.0) * SQRT3

    /** Paul Kellet's pink filter, as in [Noise.pink], fed with a cheaper Gaussian so hours generate quickly. */
    private fun pink(): Double {
        val white = fastGaussian()
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return out * PINK_TO_UNIT
    }

    companion object {
        val KINDS = listOf("whistle", "clap", "horn", "squeal", "beep", "freewheel", "voice")
        private const val NS_PER_S = 1_000_000_000L
        private const val NOISE_RMS = 0.02
        private const val PINK_TO_UNIT = 0.3
        private const val MIN_GAP_S = 2.0
        private const val MAX_GAP_S = 8.0
        private const val MIN_LEVEL_DB = 0.0
        private const val MAX_LEVEL_DB = 20.0
        private const val FADE_S = 0.02
        private const val VIBRATO = 20.0
        private const val SQUEAL_ATTACK_S = 0.15
        private const val SQUEAL_WOBBLE = 3.0
        private const val CLICK_SAMPLES = 64
        private const val CLICK_TAU_SAMPLES = 8.0
        private const val CLAP_S = 0.1
        private const val CLAP_TAU_S = 0.02
        private const val VOICE_GAIN = 6.0
        private val SQRT3 = sqrt(3.0)
    }
}
