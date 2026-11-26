package net.belllap.testing.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** `testing/thresholds.json`: a metric must be >= its `min` and <= its `max`. A threshold with no metric fails. */
@Serializable
class Thresholds(val min: Map<String, Double> = emptyMap(), val max: Map<String, Double> = emptyMap()) {
    val size: Int get() = min.size + max.size

    fun violations(metrics: Map<String, Double>): List<String> {
        val out = ArrayList<String>()
        min.forEach { (k, limit) ->
            val v = metrics[k]
            if (v == null) {
                out += "$k: no such metric"
            } else if (v < limit) {
                out += "$k = $v is below the minimum $limit"
            }
        }
        max.forEach { (k, limit) ->
            val v = metrics[k]
            if (v == null) {
                out += "$k: no such metric"
            } else if (v > limit) {
                out += "$k = $v is above the maximum $limit"
            }
        }
        return out
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun load(file: File): Thresholds = JSON.decodeFromString(file.readText())
    }
}
