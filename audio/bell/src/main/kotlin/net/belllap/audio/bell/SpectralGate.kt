package net.belllap.audio.bell

import net.belllap.audio.source.PcmChunk
import kotlin.math.ceil
import kotlin.math.log10

/**
 * A stage-0 pass: audio worth showing to the classifier. [windowEndNs] is where the classifier window should end so
 * the strike sits inside it. [kind] says whether a new strike ([Kind.ONSET]) or a bell still ringing
 * ([Kind.CONTINUATION]) produced it.
 */
data class BellCandidate(
    val kind: Kind,
    val onsetNs: Long,
    val decidedNs: Long,
    val windowEndNs: Long,
    val peakHz: Double,
    val partials: Int,
    val prominenceDb: Double,
    val decayDbPerS: Double,
) {
    enum class Kind { ONSET, CONTINUATION }
}

/**
 * Stage 0: a cheap, always-on STFT gate (512-point frames, 50% hop). It looks in [BellDetectorConfig.bandHz] for a
 * tonal peak that appears suddenly (onset), stands well above the local median spectrum, and then keeps ringing
 * while it decays. Broadband bangs (claps) have no tonal peak; whistles, squeals and horns do not decay or sit
 * outside the band. While an accepted bell keeps ringing it re-offers the audio every
 * [BellDetectorConfig.continuationNs], because a hand bell rung hard barely decays between strikes.
 *
 * Pure Kotlin, wall-clock free: every time is derived from the chunk PTS and the sample count.
 */
class SpectralGate(private val sampleRate: Int, private val config: BellDetectorConfig = BellDetectorConfig()) {
    private val fft = PowerSpectrum(FRAME)
    private val lo = maxOf(1, (config.bandHz.start * FRAME / sampleRate).toInt())
    private val hi = minOf(FRAME / 2 - 1, (config.bandHz.endInclusive * FRAME / sampleRate).toInt())
    private val bandWidth = hi - lo + 1
    private val hopNs = HOP * NS_PER_S / sampleRate
    private val probeFrames = ceil(config.minRingNs.toDouble() / hopNs).toInt() + PROBE_EXTRA_FRAMES
    private val ringFrames = ceil(config.minRingNs.toDouble() / hopNs).toInt()

    private val frame = DoubleArray(FRAME)
    private val ring = DoubleArray(FRAME)
    private var ringPos = 0
    private var sinceHop = 0
    private var samples = 0L
    private var basePts: Long? = null
    private val power = DoubleArray(FRAME / 2 + 1)
    private val history = Array(HISTORY) { DoubleArray(FRAME / 2 + 1) { FLOOR_DB } }
    private var frameIndex = 0L
    private val scratch = DoubleArray(maxOf(bandWidth, 2 * LOCAL_HALF_WIDTH + 1))

    private var probe: Probe? = null
    private var lastProbeStart = -MIN_PROBE_GAP_FRAMES.toLong()
    private var track: Track? = null

    /** Feeds one chunk of contiguous audio and returns the candidates decided inside it, in PTS order. */
    fun accept(chunk: PcmChunk): List<BellCandidate> {
        require(chunk.sampleRate == sampleRate) { "sample rate changed from $sampleRate to ${chunk.sampleRate}" }
        if (basePts == null) basePts = chunk.ptsNs
        val out = ArrayList<BellCandidate>(0)
        for (s in chunk.samples) {
            ring[ringPos] = s / PCM_SCALE
            ringPos = (ringPos + 1) % FRAME
            samples++
            if (samples >= FRAME && ++sinceHop >= HOP) {
                sinceHop = 0
                for (i in 0 until FRAME) frame[i] = ring[(ringPos + i) % FRAME]
                processFrame(out)
            }
        }
        return out
    }

    private fun frameTimeNs(): Long {
        val centre = samples - FRAME / 2
        return requireNotNull(basePts) + centre * NS_PER_S / sampleRate
    }

    private fun processFrame(out: MutableList<BellCandidate>) {
        fft.compute(frame, power)
        val db = history[(frameIndex % HISTORY).toInt()]
        val old = history[((frameIndex + 1) % HISTORY).toInt()] // HISTORY-1 frames ago
        for (k in lo..hi) db[k] = DB_PER_DECADE * log10(power[k] + POWER_EPS)
        val now = frameTimeNs()
        val peaks = tonalPeaks(db)
        val onsets = peaks.filter { p -> p.db - maxAround(old, p.bin) >= config.onsetRiseDb }

        probe?.let { pr ->
            pr.update(db)
            if (peaks.size > pr.partials && pr.frames <= PARTIAL_FRAMES) pr.partials = peaks.size
            if (pr.frames >= probeFrames) {
                probe = null
                pr.finish(now)?.let { c ->
                    out += c
                    track = Track(pr.bin, c.onsetNs, c.windowEndNs)
                }
            }
        }
        if (probe == null && onsets.isNotEmpty() && frameIndex - lastProbeStart >= MIN_PROBE_GAP_FRAMES) {
            val strongest = onsets.maxBy { it.prominence }
            probe = Probe(strongest.bin, now, peaks.size)
            lastProbeStart = frameIndex
        }
        track?.let { t -> continueTrack(t, db, now, out) }
        frameIndex++
    }

    private fun continueTrack(t: Track, db: DoubleArray, now: Long, out: MutableList<BellCandidate>) {
        t.bin = follow(db, t.bin)
        val prominence = db[t.bin] - localMedian(db, t.bin)
        t.quietFrames = if (prominence >= config.prominenceDb - SUSTAIN_SLACK_DB) 0 else t.quietFrames + 1
        if (t.quietFrames >= TRACK_LOST_FRAMES || now - t.lastOnsetNs > config.persistenceNs) {
            track = null
            return
        }
        if (t.quietFrames == 0 && now >= t.lastWindowEndNs + config.continuationNs) {
            t.lastWindowEndNs = now
            out += BellCandidate(
                BellCandidate.Kind.CONTINUATION, t.lastOnsetNs, now, now, binHz(t.bin), 1, prominence, 0.0,
            )
        }
    }

    private class Peak(val bin: Int, val db: Double, val prominence: Double)

    /** Local maxima that stand [BellDetectorConfig.prominenceDb] above the median of their neighbourhood. */
    private fun tonalPeaks(db: DoubleArray): List<Peak> {
        for (i in 0 until bandWidth) scratch[i] = db[lo + i]
        val bandMedian = select(scratch, bandWidth, bandWidth / 2)
        val out = ArrayList<Peak>(0)
        var lastBin = Int.MIN_VALUE
        for (k in lo + 1 until hi) {
            val v = db[k]
            val localMax = v >= FLOOR_DB && v >= db[k - 1] && v > db[k + 1]
            // The band median is a cheap first filter; the local median is only computed for survivors.
            val prominence = if (localMax && v - bandMedian >= config.prominenceDb - PREFILTER_SLACK_DB) {
                v - localMedian(db, k)
            } else {
                Double.NEGATIVE_INFINITY
            }
            if (prominence < config.prominenceDb) continue
            if (k - lastBin < MIN_PARTIAL_SPACING_BINS && out.isNotEmpty()) {
                if (prominence > out.last().prominence) out[out.size - 1] = Peak(k, v, prominence)
            } else {
                out += Peak(k, v, prominence)
            }
            lastBin = k
        }
        return out
    }

    private fun localMedian(db: DoubleArray, k: Int): Double {
        val from = maxOf(lo, k - LOCAL_HALF_WIDTH)
        val to = minOf(hi, k + LOCAL_HALF_WIDTH)
        val n = to - from + 1
        for (i in 0 until n) scratch[i] = db[from + i]
        return select(scratch, n, n / 2)
    }

    private fun maxAround(db: DoubleArray, k: Int): Double {
        var m = Double.NEGATIVE_INFINITY
        for (j in maxOf(lo, k - ONSET_SPREAD_BINS)..minOf(hi, k + ONSET_SPREAD_BINS)) m = maxOf(m, db[j])
        return m
    }

    private fun follow(db: DoubleArray, bin: Int): Int {
        var best = bin
        for (j in maxOf(lo, bin - FOLLOW_BINS)..minOf(hi, bin + FOLLOW_BINS)) if (db[j] > db[best]) best = j
        return best
    }

    private fun binHz(bin: Int) = bin.toDouble() * sampleRate / FRAME

    /** Follows one onset peak for [probeFrames] frames and judges whether it rang and decayed like a bell. */
    private inner class Probe(var bin: Int, val onsetNs: Long, var partials: Int) {
        var frames = 0
        private val levels = DoubleArray(probeFrames)
        private var tonalFrames = 0
        private var maxProminence = 0.0

        fun update(db: DoubleArray) {
            bin = follow(db, bin)
            val prominence = db[bin] - localMedian(db, bin)
            if (prominence >= config.prominenceDb - SUSTAIN_SLACK_DB) tonalFrames++
            maxProminence = maxOf(maxProminence, prominence)
            if (frames < probeFrames) levels[frames] = db[bin]
            frames++
        }

        fun finish(now: Long): BellCandidate? {
            val slope = decaySlopeDbPerS()
            val pass = tonalFrames >= ringFrames && -slope in config.minDecayDbPerS..config.maxDecayDbPerS &&
                partials >= config.minPartials
            if (!pass) return null
            return BellCandidate(
                BellCandidate.Kind.ONSET,
                onsetNs,
                now,
                onsetNs + config.postOnsetNs,
                binHz(bin),
                partials,
                maxProminence,
                -slope,
            )
        }

        /** Least-squares slope of the tracked level, from the loudest of the first frames to the end. */
        private fun decaySlopeDbPerS(): Double {
            var start = 0
            for (i in 1 until minOf(ATTACK_FRAMES, probeFrames)) if (levels[i] > levels[start]) start = i
            val n = probeFrames - start
            val meanX = (n - 1) / 2.0
            val meanY = (start until probeFrames).sumOf { levels[it] } / n
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                num += (i - meanX) * (levels[start + i] - meanY)
                den += (i - meanX) * (i - meanX)
            }
            return if (den == 0.0) 0.0 else num / den * NS_PER_S / hopNs
        }
    }

    private class Track(var bin: Int, val lastOnsetNs: Long, var lastWindowEndNs: Long) {
        var quietFrames = 0
    }

    companion object {
        const val FRAME = 512
        const val HOP = 256
        private const val NS_PER_S = 1_000_000_000L
        private const val PCM_SCALE = 32768.0
        private const val POWER_EPS = 1e-12
        private const val DB_PER_DECADE = 10.0
        private const val FLOOR_DB = -30.0
        private const val HISTORY = 4
        private const val LOCAL_HALF_WIDTH = 16
        private const val ONSET_SPREAD_BINS = 2
        private const val FOLLOW_BINS = 2
        private const val MIN_PARTIAL_SPACING_BINS = 4
        private const val PREFILTER_SLACK_DB = 3.0
        private const val SUSTAIN_SLACK_DB = 6.0
        private const val PROBE_EXTRA_FRAMES = 2
        private const val PARTIAL_FRAMES = 3
        private const val ATTACK_FRAMES = 3
        private const val MIN_PROBE_GAP_FRAMES = 3
        private const val TRACK_LOST_FRAMES = 3

        /** Hoare quickselect of the [k]-th smallest of the first [n] values. Reorders [a]. */
        internal fun select(a: DoubleArray, n: Int, k: Int): Double {
            var left = 0
            var right = n - 1
            while (left < right) {
                val pivot = a[(left + right) ushr 1]
                var i = left
                var j = right
                while (i <= j) {
                    while (a[i] < pivot) i++
                    while (a[j] > pivot) j--
                    if (i <= j) {
                        val t = a[i]
                        a[i] = a[j]
                        a[j] = t
                        i++
                        j--
                    }
                }
                if (k <= j) {
                    right = j
                } else if (k >= i) {
                    left = i
                } else {
                    return a[k]
                }
            }
            return a[k]
        }
    }
}
