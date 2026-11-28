package net.belllap.testing.harness

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.belllap.audio.source.WavFileAudioSource
import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.testing.fixtures.BoxLabel
import net.belllap.testing.fixtures.Labels
import net.belllap.vision.core.Frame
import net.belllap.vision.core.ImageSequenceFrameSource
import net.belllap.vision.flag.FlagBox
import net.belllap.vision.flag.FlagConfig
import net.belllap.vision.flag.FlagResult
import java.io.File

/** Replays fixtures from disk (through the file-backed sources) into the detectors and computes metrics. */
object ReplayRunner {
    private const val NS_PER_S = 1e9
    private const val NS_PER_MS = 1e6
    private const val BELL_CHUNK_MS = 20

    /** Every bell stage event of the last [run], as `fixture,kind,pts_ms,detail` CSV rows. */
    val bellTrace = ArrayList<String>()

    /** One row per frame of every flag fixture of the last [run]: `fixture,frame,pts_ms,visible,box,texture_box`. */
    val flagTrace = ArrayList<String>()

    fun run(
        fixturesRoot: File,
        names: List<String>,
        stage1: Stage1 = Stage1.Off,
        flagConfig: FlagConfig = FlagConfig(),
    ): Map<String, Double> {
        val out = sortedMapOf<String, Double>()
        flagTrace.clear()
        for (name in names) {
            val dir = File(fixturesRoot, name)
            val labels = Labels.load(File(dir, Labels.FILE_NAME))
            val prefix = if (name == "race_synth") "race" else name
            if (labels.audioFile != null) bell(dir, labels, prefix, out, stage1)
            if (labels.framesDir != null) {
                val frames = loadFrames(dir, labels)
                if (labels.flagIntervals.isNotEmpty() || labels.distractors.any { it.kind == Labels.BANNER_KIND }) {
                    flag(frames, labels, prefix, out, flagConfig)
                }
                if (labels.crossings.isNotEmpty()) crossings(frames, labels, prefix, out, line(labels))
            }
        }
        return out
    }

    /** Recall and worst latency over every fixture with bell labels: the F14 acceptance numbers. */
    fun bellTotals(metrics: Map<String, Double>): Map<String, Double> {
        val prefixes = metrics.keys.filter { it.endsWith(".bell.episodes") }.map { it.removeSuffix(".bell.episodes") }
        if (prefixes.isEmpty()) return emptyMap()
        val episodes = prefixes.sumOf { metrics.getValue("$it.bell.episodes") }
        val matched = prefixes.sumOf { metrics.getValue("$it.bell.matched") }
        return mapOf(
            "all.bell.recall" to if (episodes == 0.0) 1.0 else matched / episodes,
            "all.bell.max_latency_ms" to prefixes.maxOf { metrics.getValue("$it.bell.max_latency_ms") },
        )
    }

    fun loadFrames(dir: File, labels: Labels): List<Frame> = runBlocking {
        ImageSequenceFrameSource.fromDirectory(
            File(dir, requireNotNull(labels.framesDir)),
            requireNotNull(labels.fps),
        ).frames().toList()
    }

    private fun bell(dir: File, labels: Labels, prefix: String, out: MutableMap<String, Double>, stage1: Stage1) {
        val source = WavFileAudioSource(File(dir, requireNotNull(labels.audioFile)), BELL_CHUNK_MS)
        val run = BellRun(stage1, sampleRate = source.sampleRate)
        runBlocking { source.chunks().toList() }.forEach(run::accept)
        val r = BellMetrics.score(labels.bellOnsetsNs, run.detectionsNs)
        bellTrace += run.trace.map { "$prefix,$it" }
        out["$prefix.bell.recall"] = if (r.episodes == 0) 1.0 else r.matched.toDouble() / r.episodes
        val seconds = run.samples.toDouble() / source.sampleRate
        out["$prefix.bell.false_alarms_per_hour"] = Metrics.falseAlarmsPerHour(r.falseAlarms, seconds)
        out["$prefix.bell.max_latency_ms"] = (r.latenciesNs.maxOrNull() ?: 0L) / NS_PER_MS
        out["$prefix.bell.mean_latency_ms"] = r.latenciesNs.map { it.toDouble() }.average().takeIf { !it.isNaN() }
            ?.div(NS_PER_MS) ?: 0.0
        out["$prefix.bell.candidates"] = run.candidates.toDouble()
        out["$prefix.bell.episodes"] = r.episodes.toDouble()
        out["$prefix.bell.matched"] = r.matched.toDouble()
    }

    private fun flag(
        frames: List<Frame>,
        labels: Labels,
        prefix: String,
        out: MutableMap<String, Double>,
        config: FlagConfig,
    ) {
        val run = FlagRun.over(frames, config)
        run.results.forEachIndexed { i, r ->
            flagTrace += "$prefix,$i,${frames[i].ptsNs / NS_PER_MS},${r.visible},${r.box.csv()},${r.textureBox.csv()}"
        }
        val found = run.intervals
        val truth = labels.flagIntervals.map { it.startNs until it.endNs }
        out["$prefix.flag.iou"] = Metrics.intervalIou(truth, found)
        out["$prefix.flag.interval_count_error"] = Math.abs(found.size - truth.size).toDouble()
        out["$prefix.flag.max_start_error_ms"] = truth.zip(found).maxOfOrNull {
                (t, f) ->
            Math.abs(f.first - t.first) / NS_PER_MS
        } ?: 0.0
        if (labels.flagBoxes.isNotEmpty()) flagBoxes(frames, run, labels, prefix, out)
        if (truth.isEmpty()) {
            // A distractor-only fixture: any visible frame is a false positive. (With a real flag, hysteresis lag
            // at the edges is not one, and shows in iou and max_start_error_ms instead.)
            val frameMs = NS_PER_S / requireNotNull(labels.fps) / NS_PER_MS
            out["$prefix.flag.false_visible_ms"] = run.visible.count { it } * frameMs
        }
    }

    /**
     * Localisation on the labelled keyframes. `box_recall`: share of keyframes where the reported flag box overlaps
     * the label. `box_precision`: of the keyframes with a reported box, the share that overlaps. `texture_recall`:
     * as `box_recall` for the texture-only box, so a miss can be blamed on texture or on motion.
     */
    private fun flagBoxes(
        frames: List<Frame>,
        run: FlagRun,
        labels: Labels,
        prefix: String,
        out: MutableMap<String, Double>,
    ) {
        val keys = labels.flagBoxes.filter { it.frame < frames.size }
        fun hits(pick: (FlagResult) -> FlagBox?) = keys.count { k ->
            val f = frames[k.frame]
            pick(run.results[k.frame])?.overlaps(k, f.width, f.height) == true
        }
        val reported = keys.count { run.results[it.frame].box != null }
        out["$prefix.flag.box_recall"] = hits { it.box }.toDouble() / keys.size
        out["$prefix.flag.box_precision"] = if (reported == 0) 0.0 else hits { it.box }.toDouble() / reported
        out["$prefix.flag.texture_recall"] = hits { it.textureBox }.toDouble() / keys.size
    }

    private fun FlagBox.overlaps(k: BoxLabel, w: Int, h: Int): Boolean {
        val kx0 = k.x * w
        val ky0 = k.y * h
        return x < kx0 + k.width * w && kx0 < x + width && y < ky0 + k.height * h && ky0 < y + height
    }

    /** The fixture's labelled finish line; synthetic fixtures carry none and use the scene's line. */
    private fun line(labels: Labels): FinishLine = labels.finishLine?.let {
        FinishLine(NormPoint(it.x1, it.y1), NormPoint(it.x2, it.y2))
    } ?: CrossingRun.SYNTH_LINE

    private fun FlagBox?.csv(): String = this?.let { "${it.x} ${it.y} ${it.width} ${it.height}" }.orEmpty()

    private fun crossings(
        frames: List<Frame>,
        labels: Labels,
        prefix: String,
        out: MutableMap<String, Double>,
        line: FinishLine,
    ) {
        val run = CrossingRun.over(frames, line)
        val frameNs = NS_PER_S / requireNotNull(labels.fps)
        crossingMetrics("$prefix.crossings", labels, run.live.map { it.ptsNs }, frameNs, out)
        crossingMetrics("$prefix.crossings_refined", labels, run.refined.map { it.ptsNs }, frameNs, out)
    }

    /**
     * Matches detections to labels within [CROSSING_TOLERANCE_FRAMES] frames. `count_error_per_bunch` is the worst
     * |detected - labelled| over bunches, with each detection counted in the bunch of the nearest label in time.
     */
    private fun crossingMetrics(
        key: String,
        labels: Labels,
        found: List<Long>,
        frameNs: Double,
        out: MutableMap<String, Double>,
    ) {
        val truth = labels.crossings
        val sorted = found.sorted()
        val m = Metrics.match(truth.map { it.ptsNs }, sorted, Math.round(frameNs * CROSSING_TOLERANCE_FRAMES))
        val detectedByGroup = sorted.groupingBy { t -> truth.minBy { Math.abs(it.ptsNs - t) }.group }.eachCount()
        val labelledByGroup = truth.groupingBy { it.group }.eachCount()
        out["$key.recall"] = Metrics.recall(m)
        out["$key.precision"] = Metrics.precision(m)
        out["$key.mean_abs_error_frames"] = Metrics.meanAbsError(m) / frameNs
        out["$key.max_abs_error_frames"] = (m.errorsNs.maxOfOrNull { Math.abs(it) } ?: 0L) / frameNs
        out["$key.count_error"] = Math.abs(sorted.size - truth.size).toDouble()
        out["$key.count_error_per_bunch"] = labelledByGroup.maxOfOrNull { (g, n) ->
            Math.abs((detectedByGroup[g] ?: 0) - n).toDouble()
        } ?: 0.0
    }

    private const val CROSSING_TOLERANCE_FRAMES = 2
}
