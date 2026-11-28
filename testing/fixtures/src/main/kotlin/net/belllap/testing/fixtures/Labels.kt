package net.belllap.testing.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A half-open time span `[startNs, endNs)` on the fixture's PTS time base. */
@Serializable
data class IntervalNs(val startNs: Long, val endNs: Long)

/** A sound that must NOT trigger the bell detector. */
@Serializable
data class DistractorLabel(val kind: String, val startNs: Long)

/** One rider's front wheel reaching the finish line. [group] numbers the bunch; riders in a bunch share it. */
@Serializable
data class CrossingLabel(val frame: Int, val ptsNs: Long, val group: Int)

/**
 * The finish line on a real clip, as fractions (0..1) of the frame from ([x1], [y1]) to ([x2], [y2]). The clip must
 * keep it still: a panning source is stabilised first (`scripts/fixtures/clip-crossings.py`).
 */
@Serializable
data class LineLabel(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * Where the flag is on one keyframe of a real clip, as fractions (0..1) of the frame. Lets the harness check that a
 * detection is on the flag and not on something else that happens to be chequered or moving.
 */
@Serializable
data class BoxLabel(val frame: Int, val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Ground truth for one fixture, stored as `labels.json` next to the media. All times are PTS in nanoseconds.
 * The same schema is used for real footage (F03b), so fields a fixture lacks are simply empty.
 */
@Serializable
data class Labels(
    val schemaVersion: Int = SCHEMA_VERSION,
    val name: String,
    val seed: Long? = null,
    val audioFile: String? = null,
    val sampleRate: Int? = null,
    val framesDir: String? = null,
    val fps: Double? = null,
    val bellOnsetsNs: List<Long> = emptyList(),
    val flagIntervals: List<IntervalNs> = emptyList(),
    val crossings: List<CrossingLabel> = emptyList(),
    val distractors: List<DistractorLabel> = emptyList(),
    /** Synthetic fixtures leave this null and use the scene's own line. */
    val finishLine: LineLabel? = null,
    /** Flag position on some frames inside [flagIntervals]; optional, sorted by frame. */
    val flagBoxes: List<BoxLabel> = emptyList(),
) {
    /** Fails fast on a malformed file instead of letting a metric quietly come out wrong. */
    fun validate(): Labels {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported labels schemaVersion $schemaVersion" }
        require(
            bellOnsetsNs.all {
                it >= 0
            } && bellOnsetsNs == bellOnsetsNs.sorted(),
        ) { "bellOnsetsNs must be sorted and >= 0" }
        require(flagIntervals.all { it.startNs in 0 until it.endNs }) { "flag intervals need 0 <= start < end" }
        flagIntervals.zipWithNext().forEach {
                (a, b) ->
            require(a.endNs <= b.startNs) { "flag intervals overlap or are unsorted" }
        }
        require(crossings.all { it.frame >= 0 && it.ptsNs >= 0 }) { "crossings need non-negative frame and pts" }
        require(crossings.map { it.ptsNs } == crossings.map { it.ptsNs }.sorted()) { "crossings must be sorted by pts" }
        finishLine?.let { l ->
            require(listOf(l.x1, l.y1, l.x2, l.y2).all { it in 0f..1f }) { "finishLine must be within 0..1" }
            require(l.x1 != l.x2 || l.y1 != l.y2) { "finishLine needs two distinct points" }
        }
        require(flagBoxes.map { it.frame } == flagBoxes.map { it.frame }.sorted().distinct()) {
            "flagBoxes must be sorted by frame, one per frame"
        }
        require(
            flagBoxes.all { b ->
                b.frame >= 0 && b.width > 0f && b.height > 0f && b.x >= 0f && b.y >= 0f &&
                    b.x + b.width <= 1f + EPS && b.y + b.height <= 1f + EPS
            },
        ) { "flagBoxes must lie within the frame" }
        return this
    }

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1
        private const val EPS = 1e-4f
        const val FILE_NAME = "labels.json"

        /** [DistractorLabel.kind] of a static chequered banner in view: no flag may be reported. */
        const val BANNER_KIND = "chequered_banner"

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        fun parse(text: String): Labels = Json.decodeFromString<Labels>(text).validate()

        fun load(file: File): Labels = parse(file.readText())
    }
}
