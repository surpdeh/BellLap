package net.belllap.audio.bell

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hann-windowed power spectrum of a fixed-size real frame, by an in-place radix-2 FFT.
 * One instance owns its scratch buffers, so it is not thread safe; a detector holds its own.
 */
class PowerSpectrum(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val bits = Integer.numberOfTrailingZeros(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (Int.SIZE_BITS - bits) }

    /** Number of bins written by [compute]: DC to Nyquist inclusive. */
    val bins: Int get() = size / 2 + 1

    /** Writes |X[k]|^2 for k in 0..size/2 into [out] from [frame] (samples scaled to [-1, 1]). */
    fun compute(frame: DoubleArray, out: DoubleArray) {
        require(frame.size == size && out.size >= bins) { "frame or output has the wrong size" }
        for (i in 0 until size) {
            re[reversed[i]] = frame[i] * window[i]
            im[reversed[i]] = 0.0
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (j in 0 until half) {
                    val wr = cosTable[j * step]
                    val wi = sinTable[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }
        for (k in 0 until bins) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}
