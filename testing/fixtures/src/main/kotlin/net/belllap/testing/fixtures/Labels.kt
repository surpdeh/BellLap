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
        return this
    }

    fun encode(): String = JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1
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
