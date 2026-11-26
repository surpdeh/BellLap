package net.belllap.testing.fixtures

import kotlin.math.sqrt
import kotlin.random.Random

/** Seeded noise sources. Same seed, same samples, on every machine. */
object Noise {
    /** Approximately Gaussian, via the sum of 12 uniforms. */
    fun gaussian(rng: Random): Double {
        var s = 0.0
        repeat(12) { s += rng.nextDouble() }
        return s - 6.0
    }

    /** Pink (1/f) noise, Paul Kellet's filter. Unit-ish variance is not guaranteed: callers scale to a target power. */
    fun pink(n: Int, rng: Random): DoubleArray {
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        return DoubleArray(n) {
            val white = gaussian(rng)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
            out
        }
    }

    fun power(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return s / maxOf(1, to - from)
    }

    fun rms(x: DoubleArray) = sqrt(power(x))
}
