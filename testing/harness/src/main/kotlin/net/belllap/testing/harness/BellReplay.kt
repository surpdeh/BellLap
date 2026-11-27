package net.belllap.testing.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.belllap.audio.bell.AcceptAllClassifier
import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.bell.BellDetector
import net.belllap.audio.bell.BellDetectorConfig
import net.belllap.audio.bell.BellTrace
import net.belllap.audio.bell.YamnetClassMap
import net.belllap.audio.source.PcmChunk
import net.belllap.testing.fixtures.AmbienceStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Bell metrics are per bell lap ("episode"), not per strike: the app needs one BellDetected per bell lap. */
object BellMetrics {
    /** A detection counts for an episode if it lands within this long after the first strike. */
    const val MATCH_WINDOW_NS = 5_000_000_000L

    /** Strikes closer than this belong to the same bell lap. */
    const val EPISODE_GAP_NS = 3_000_000_000L

    class Result(val episodes: Int, val matched: Int, val falseAlarms: Int, val latenciesNs: List<Long>)

    fun episodes(strikesNs: List<Long>): List<Long> {
        val out = ArrayList<Long>()
        var last = Long.MIN_VALUE
        for (s in strikesNs.sorted()) {
            if (out.isEmpty() || s - last > EPISODE_GAP_NS) out += s
            last = s
        }
        return out
    }

    /** Each episode takes the first unused detection in `[start, start + MATCH_WINDOW_NS]`. */
    fun score(strikesNs: List<Long>, detectionsNs: List<Long>): Result {
        val episodes = episodes(strikesNs)
        val used = BooleanArray(detectionsNs.size)
        val latencies = ArrayList<Long>()
        for (e in episodes) {
            val i = detectionsNs.indices.firstOrNull { !used[it] && detectionsNs[it] in e..e + MATCH_WINDOW_NS }
            if (i != null) {
                used[i] = true
                latencies += detectionsNs[i] - e
            }
        }
        return Result(episodes.size, latencies.size, used.count { !it }, latencies)
    }
}

/**
 * How stage 1 runs in the harness. LiteRT has no desktop JVM runtime, so YAMNet runs out of process
 * (`scripts/bell/replay-yamnet.sh`): one pass [dumping][Dump] every window stage 0 would hand to YAMNet, a Python
 * pass scoring them with the real model, and a pass replaying with those [precomputed][Precomputed] scores.
 */
sealed interface Stage1 {
    fun classifier(): BellClassifier

    val label: String

    /** Stage 0 and persistence only: an upper bound on false alarms (stage 1 can only remove candidates). */
    data object Off : Stage1 {
        override fun classifier() = AcceptAllClassifier

        override val label = "stage 1 off (stage 0 + persistence)"
    }

    /** Scores 0 so persistence never fires and every candidate window gets written to [dir]. */
    class Dump(val dir: File) : Stage1 {
        override val label = "dumping candidate windows"

        override fun classifier() = object : BellClassifier {
            override val windowSamples = YamnetClassMap.WINDOW_SAMPLES
            override val name = "dump"

            override fun bellScore(window: FloatArray): Float {
                dir.mkdirs()
                val bytes = ByteBuffer.allocate(window.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                window.forEach { bytes.putFloat(it) }
                File(dir, "${windowKey(window)}.f32").writeBytes(bytes.array())
                return 0f
            }
        }
    }

    /** Bell-family scores from `yamnet_score.py`: `{"labels": [...], "scores": {key: [521 floats]}}`. */
    class Precomputed(file: File) : Stage1 {
        private val map: YamnetClassMap
        private val scores: Map<String, Float>

        init {
            val doc = Json.parseToJsonElement(file.readText()) as JsonObject
            map = YamnetClassMap(requireNotNull(doc["labels"]).jsonArray.map { it.jsonPrimitive.content })
            scores = (requireNotNull(doc["scores"]) as JsonObject).mapValues { (_, v) ->
                map.bellScore((v as JsonArray).map { it.jsonPrimitive.float }.toFloatArray())
            }
        }

        override val label = "YAMNet (precomputed by scripts/bell/yamnet_score.py)"

        override fun classifier() = object : BellClassifier {
            override val windowSamples = YamnetClassMap.WINDOW_SAMPLES
            override val name = "yamnet-precomputed"

            override fun bellScore(window: FloatArray): Float = scores[windowKey(window)]
                ?: error("no YAMNet score for window ${windowKey(window)}; rerun scripts/bell/replay-yamnet.sh")
        }
    }

    companion object {
        /** 64-bit FNV-1a over the float bits: names a window identically in Kotlin and in the score file. */
        fun windowKey(window: FloatArray): String {
            var h = -0x340d631b7bdddcdbL
            for (f in window) {
                val bits = f.toRawBits()
                for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) {
                    h = (h xor ((bits ushr shift) and BYTE_MASK).toLong()) * FNV_PRIME
                }
            }
            return java.lang.Long.toHexString(h).padStart(HEX_DIGITS, '0')
        }

        private const val FNV_PRIME = 0x100000001b3L
        private const val BYTE_MASK = 0xFF
        private const val HEX_DIGITS = 16

        /** From system properties set by the Gradle `replay` task (`-Pbell.dump=dir` or `-Pbell.scores=file`). */
        fun fromSystemProperties(): Stage1 {
            val scores = System.getProperty("belllap.bell.scores")?.takeIf { it.isNotBlank() }
            val dump = System.getProperty("belllap.bell.dump")?.takeIf { it.isNotBlank() }
            return when {
                scores != null -> Precomputed(File(scores))
                dump != null -> Dump(File(dump))
                else -> Off
            }
        }
    }
}

/** Runs the bell cascade over audio and records what it did. */
class BellRun(stage1: Stage1, config: BellDetectorConfig = BellDetectorConfig(), sampleRate: Int = 16_000) {
    val detectionsNs = ArrayList<Long>()

    /** One CSV row per stage event: `kind,pts_ms,detail`. */
    val trace = ArrayList<String>()
    var candidates = 0
        private set
    var scored = 0
        private set
    var samples = 0L
        private set
    private val detector = BellDetector(sampleRate, stage1.classifier(), config) {
        when (it) {
            is BellTrace.Candidate -> {
                candidates++
                val c = it.candidate
                trace += "${c.kind.name.lowercase()},${ms(c.onsetNs)},window_end=${ms(c.windowEndNs)} " +
                    "hz=${c.peakHz} partials=${c.partials} decay_db_s=${"%.1f".format(c.decayDbPerS)}"
            }
            is BellTrace.Scored -> {
                scored++
                trace += "scored,${ms(it.ptsNs)},score=${"%.3f".format(it.score)} hit=${it.hit}"
            }
            is BellTrace.Detected ->
                trace += "detected,${ms(it.ptsNs)},reason=${it.reason} hits=${it.hits} first=${ms(it.firstHitNs)}"
        }
    }

    fun accept(chunk: PcmChunk) {
        detectionsNs += detector.accept(chunk)
        samples += chunk.samples.size
    }
}

private fun ms(ns: Long) = "%.0f".format(ns / 1e6)

/** Long no-bell runs over [AmbienceStream], one stream per hour, in parallel. */
object AmbienceReplay {
    private const val CHUNK = 320
    private const val SAMPLES_PER_HOUR = 16_000L * 3600

    class Result(val hours: Int, val falseAlarms: Int, val candidates: Int, val distractors: Map<String, Int>)

    fun run(hours: Int, stage1: Stage1, seed: Long): Result {
        val distractors = ConcurrentHashMap<String, Int>()
        val pool = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))
        try {
            val runs = (0 until hours).map { h ->
                pool.submit<BellRun> {
                    val stream = AmbienceStream(seed + h)
                    val run = BellRun(stage1)
                    repeat((SAMPLES_PER_HOUR / CHUNK).toInt()) { run.accept(stream.next(CHUNK)) }
                    stream.distractorCounts.forEach { (k, v) -> distractors.merge(k, v, Int::plus) }
                    run
                }
            }.map { it.get() }
            val falseAlarms = runs.sumOf { it.detectionsNs.size }
            return Result(hours, falseAlarms, runs.sumOf { it.candidates }, distractors.toSortedMap())
        } finally {
            pool.shutdown()
        }
    }
}
