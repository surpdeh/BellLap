package net.belllap.audio.bell

import net.belllap.audio.source.PcmChunk

/** What the cascade did, for the event log and the replay harness. Times are audio PTS. */
sealed interface BellTrace {
    val ptsNs: Long

    /** Stage 0 passed some audio on. */
    data class Candidate(val candidate: BellCandidate) : BellTrace {
        override val ptsNs: Long get() = candidate.decidedNs
    }

    /** Stage 1 scored the window ending at [ptsNs]. [hit] is score >= threshold. */
    data class Scored(override val ptsNs: Long, val score: Float, val hit: Boolean) : BellTrace

    /** Emit BellDetected. [firstHitNs] is the oldest hit (or strike) that counted. */
    data class Detected(override val ptsNs: Long, val firstHitNs: Long, val hits: Int, val reason: Reason) : BellTrace

    enum class Reason {
        /** [BellDetectorConfig.hitsRequired] classifier hits within the persistence window. */
        CLASSIFIER,

        /** [BellDetectorConfig.strikeTrain] stage-0 strikes within the persistence window. */
        STRIKE_TRAIN,
    }
}

/**
 * The bell cascade: [SpectralGate] (stage 0), then [classifier] on each candidate's window (stage 1), then
 * persistence: [BellDetectorConfig.hitsRequired] hits within [BellDetectorConfig.persistenceNs] fire one
 * detection, followed by a refractory period so one bell lap gives one event.
 *
 * A bell lap is rung repeatedly, so a train of [BellDetectorConfig.strikeTrain] stage-0 strikes inside the same
 * window also fires, whatever the classifier says. Stage 0 alone almost never passes ambience (see the
 * `ambience.bell.candidates_per_hour` metric), while YAMNet scores a real bell low when the noise is loud.
 *
 * Feed contiguous chunks in order. [accept] returns the detections decided inside the chunk; [onTrace] sees
 * every stage. Pure Kotlin and wall-clock free, so the same code runs live, in JVM tests and in Replay.
 */
class BellDetector(
    private val sampleRate: Int,
    private val classifier: BellClassifier,
    private val config: BellDetectorConfig = BellDetectorConfig(),
    private val onTrace: (BellTrace) -> Unit = {},
) {
    init {
        require(sampleRate == YamnetClassMap.SAMPLE_RATE) { "the bell detector needs 16 kHz audio, got $sampleRate" }
    }

    private val gate = SpectralGate(sampleRate, config)
    private val window = classifier.windowSamples
    private val history = FloatArray(window + sampleRate * HISTORY_SLACK_S)
    private var written = 0L
    private var basePts: Long? = null
    private val pending = ArrayDeque<BellCandidate>()
    private val hits = ArrayDeque<Long>()
    private val strikes = ArrayDeque<Long>()
    private var lastScoredEndNs = Long.MIN_VALUE
    private var refractoryUntilNs = Long.MIN_VALUE

    /** Feeds one chunk. Returns the PTS of each BellDetected decided in it. */
    fun accept(chunk: PcmChunk): List<Long> {
        if (basePts == null) basePts = chunk.ptsNs
        val candidates = gate.accept(chunk)
        for (s in chunk.samples) {
            history[(written % history.size).toInt()] = s / PCM_SCALE
            written++
        }
        val out = ArrayList<Long>(0)
        candidates.forEach {
            onTrace(BellTrace.Candidate(it))
            if (it.decidedNs < refractoryUntilNs) return@forEach
            pending.addLast(it)
            if (it.kind == BellCandidate.Kind.ONSET) strike(it.decidedNs)?.let(out::add)
        }
        val availableNs = ptsOfSample(written)
        while (pending.isNotEmpty() && pending.first().windowEndNs <= availableNs) {
            score(pending.removeFirst())?.let { out += it }
        }
        return out
    }

    private fun score(c: BellCandidate): Long? {
        val end = c.windowEndNs
        // Windows that nearly coincide hold the same audio: score once, count once.
        val duplicate = lastScoredEndNs != Long.MIN_VALUE && end - lastScoredEndNs < DEDUPE_NS
        if (end < refractoryUntilNs || duplicate) return null
        val score = classifier.bellScore(windowEndingAt(end))
        lastScoredEndNs = end
        val hit = score >= config.scoreThreshold
        onTrace(BellTrace.Scored(end, score, hit))
        if (hit) {
            hits.addLast(end)
            while (end - hits.first() > config.persistenceNs) hits.removeFirst()
        }
        return if (hit && hits.size >= config.hitsRequired) {
            detect(end, hits.first(), hits.size, BellTrace.Reason.CLASSIFIER)
        } else {
            null
        }
    }

    private fun strike(at: Long): Long? {
        strikes.addLast(at)
        while (at - strikes.first() > config.persistenceNs) strikes.removeFirst()
        if (strikes.size < config.strikeTrain) return null
        return detect(at, strikes.first(), strikes.size, BellTrace.Reason.STRIKE_TRAIN)
    }

    private fun detect(at: Long, first: Long, count: Int, reason: BellTrace.Reason): Long {
        onTrace(BellTrace.Detected(at, first, count, reason))
        hits.clear()
        strikes.clear()
        pending.clear()
        refractoryUntilNs = at + config.refractoryNs
        return at
    }

    private fun windowEndingAt(endNs: Long): FloatArray {
        val endSample = ((endNs - requireNotNull(basePts)) * sampleRate / NS_PER_S).coerceAtMost(written)
        val out = FloatArray(window)
        val oldest = maxOf(0L, written - history.size)
        for (i in 0 until window) {
            val idx = endSample - window + i
            if (idx >= oldest) out[i] = history[(idx % history.size).toInt()]
        }
        return out
    }

    private fun ptsOfSample(n: Long) = requireNotNull(basePts) + n * NS_PER_S / sampleRate

    private companion object {
        const val PCM_SCALE = 32768f
        const val NS_PER_S = 1_000_000_000L
        const val HISTORY_SLACK_S = 2
        const val DEDUPE_NS = 240_000_000L
    }
}
