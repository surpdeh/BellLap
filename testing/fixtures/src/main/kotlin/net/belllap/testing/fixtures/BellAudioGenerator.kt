package net.belllap.testing.fixtures

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class BellAudio(
    val sampleRate: Int,
    val samples: ShortArray,
    val onsetsNs: List<Long>,
    val distractors: List<DistractorLabel>,
)

private data class ToneSpec(val hz: Double, val seconds: Double, val vibratoHz: Double)

/**
 * Bell strikes (inharmonic partials with an exponential decay) on pink noise at a chosen SNR, plus optional
 * distractors that sound loud but are not the bell: a whistle, hand claps and a car horn.
 * SNR is the mean power of the bell while it rings over the mean power of the noise.
 */
class BellAudioGenerator(
    private val seed: Long,
    private val sampleRate: Int = DEFAULT_RATE,
    private val durationS: Double = 30.0,
    private val onsetsS: List<Double> = listOf(8.0, 17.0),
    private val snrDb: Double = 10.0,
    private val distractors: Boolean = false,
) {
    fun generate(): BellAudio {
        val rng = Random(seed)
        val n = (durationS * sampleRate).toInt()
        val bell = DoubleArray(n)
        onsetsS.forEach { addBell(bell, (it * sampleRate).toInt()) }
        val bellPower = ringingPower(bell)
        val noise = Noise.pink(n, rng)
        val noiseScale = Math.sqrt(bellPower / 10.0.pow(snrDb / 10.0) / Noise.power(noise))
        val mix = DoubleArray(n) { bell[it] + noise[it] * noiseScale }
        val labels = if (distractors) addDistractors(mix, bellPower, rng) else emptyList()
        val peak = mix.maxOf { kotlin.math.abs(it) }
        val gain = PEAK * Short.MAX_VALUE / max(peak, 1e-9)
        return BellAudio(
            sampleRate,
            ShortArray(n) {
                (mix[it] * gain).toInt().toShort()
            },
            onsetsS.map { (it * 1e9).toLong() },
            labels,
        )
    }

    private fun addBell(out: DoubleArray, start: Int) {
        val len = (RING_S * sampleRate).toInt()
        for (i in 0 until minOf(len, out.size - start)) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t / DECAY_S) * minOf(1.0, t / ATTACK_S)
            var s = 0.0
            PARTIALS.forEachIndexed { k, ratio -> s += sin(2 * PI * F0 * ratio * t) / (k + 1) }
            out[start + i] += env * s
        }
    }

    /** Mean power over the first 0.5 s of each strike: the part a listener would call "the bell". */
    private fun ringingPower(bell: DoubleArray): Double {
        val span = (RING_POWER_S * sampleRate).toInt()
        val starts = onsetsS.map { (it * sampleRate).toInt() }
        return starts.sumOf { Noise.power(bell, it, minOf(bell.size, it + span)) } / starts.size
    }

    private fun addDistractors(mix: DoubleArray, bellPower: Double, rng: Random): List<DistractorLabel> {
        val amp = Math.sqrt(bellPower * 2) // sine of equal power to the bell
        val free = generateSequence(DISTRACTOR_GAP_S) { it + DISTRACTOR_STEP_S }
            .takeWhile { it < durationS - DISTRACTOR_GAP_S }
            .filter { t -> onsetsS.none { kotlin.math.abs(it - t) < DISTRACTOR_GAP_S } }
            .toList()
        val kinds = listOf("whistle", "clap", "horn")
        return free.take(kinds.size * 2).mapIndexed { i, t ->
            val kind = kinds[i % kinds.size]
            val start = (t * sampleRate).toInt()
            when (kind) {
                "whistle" -> tone(mix, start, ToneSpec(WHISTLE_HZ, WHISTLE_S, vibratoHz = 5.0), amp)
                "horn" -> tone(mix, start, ToneSpec(HORN_HZ, HORN_S, vibratoHz = 0.0), amp)
                else -> clap(mix, start, amp, rng)
            }
            DistractorLabel(kind, (t * 1e9).toLong())
        }
    }

    private fun tone(out: DoubleArray, start: Int, spec: ToneSpec, amp: Double) {
        val (hz, seconds, vibratoHz) = spec
        for (i in 0 until minOf((seconds * sampleRate).toInt(), out.size - start)) {
            val t = i.toDouble() / sampleRate
            val fade = minOf(1.0, t / 0.02, (seconds - t) / 0.02)
            val vibrato = if (vibratoHz > 0) VIBRATO_DEPTH * sin(2 * PI * vibratoHz * t) else 0.0
            out[start + i] += amp * fade * sin(2 * PI * hz * t + vibrato)
        }
    }

    private fun clap(out: DoubleArray, start: Int, amp: Double, rng: Random) {
        for (i in 0 until minOf((CLAP_S * sampleRate).toInt(), out.size - start)) {
            out[start + i] += amp * 1.5 * Noise.gaussian(rng) * exp(-i.toDouble() / sampleRate / CLAP_TAU_S)
        }
    }

    companion object {
        /**
         * Strike times of one bell lap: a ring of [strikes] strikes 0.3 to 0.45 s apart starting at [startS],
         * the way a commissaire swings a hand bell.
         */
        fun ring(startS: Double, rng: Random, strikes: Int = RING_STRIKES): List<Double> {
            val out = ArrayList<Double>(strikes)
            var t = startS
            repeat(strikes) {
                out += t
                t += rng.nextDouble(STRIKE_GAP_MIN_S, STRIKE_GAP_MAX_S)
            }
            return out
        }

        const val RING_STRIKES = 6
        private const val STRIKE_GAP_MIN_S = 0.3
        private const val STRIKE_GAP_MAX_S = 0.45
        const val DEFAULT_RATE = 16_000
        const val F0 = 1800.0
        val PARTIALS = listOf(1.0, 2.0, 2.76)
        private const val RING_S = 1.5
        private const val RING_POWER_S = 0.5
        private const val DECAY_S = 0.35
        private const val ATTACK_S = 0.002
        private const val PEAK = 0.9
        private const val DISTRACTOR_GAP_S = 3.0
        private const val DISTRACTOR_STEP_S = 2.5
        private const val WHISTLE_HZ = 2950.0
        private const val WHISTLE_S = 0.6
        private const val HORN_HZ = 420.0
        private const val HORN_S = 0.8
        private const val VIBRATO_DEPTH = 20.0
        private const val CLAP_S = 0.1
        private const val CLAP_TAU_S = 0.02
    }
}
