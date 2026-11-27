package net.belllap.testing.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.belllap.audio.source.WavIo
import net.belllap.testing.fixtures.EvidenceImages
import net.belllap.testing.fixtures.FixtureWriter
import net.belllap.testing.fixtures.Fixtures
import net.belllap.testing.fixtures.Labels
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val SHEET_COLUMNS = 6
private const val SHEET_FRAMES = 18
private val PRETTY = Json { prettyPrint = true }

/**
 * `./gradlew :testing:harness:replayReal`. Args: output dir, default real-fixtures dir.
 * Reports metrics on real footage when present (reporting only, not gating). Skips cleanly with exit 0 when absent.
 */
fun replayRealMain(args: Array<String>) {
    val outDir = File(args[0]).also { it.mkdirs() }
    val root = RealFixtures.resolveRoot(default = File(args[1]))
    val names = RealFixtures.discover(root)
    val file = File(outDir, "real-metrics.json")
    if (names.isEmpty()) {
        println("SKIPPED: no real fixtures under ${root.path} (run scripts/fixtures/fetch.sh). Result: inconclusive.")
        file.writeText(
            PRETTY.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("status", "skipped")
                    put("fixtures", JsonArray(emptyList()))
                    put("metrics", buildJsonObject { })
                },
            ) + "\n",
        )
        return
    }
    val metrics = ReplayRunner.run(root, names)
    file.writeText(
        PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", 1)
                put("status", "ran")
                put("fixtures", JsonArray(names.map(::JsonPrimitive)))
                put("metrics", buildJsonObject { metrics.forEach { (k, v) -> put(k, v) } })
            },
        ) + "\n",
    )
    metrics.forEach { (k, v) -> println("%-48s %10.3f".format(k, v)) }
    println("${names.size} real fixtures, ${metrics.size} metrics. Wrote ${file.path}")
}

/**
 * `./gradlew :testing:harness:replay`. Args: output dir, thresholds file.
 * Writes `metrics.json`, `contact-sheet.png` and `spectrogram.png`, and exits 1 if any threshold is violated.
 */
fun main(args: Array<String>) {
    val outDir = File(args[0]).also { it.mkdirs() }
    val thresholds = Thresholds.load(File(args[1]))
    val fixturesDir = File(outDir.parentFile, "replay-fixtures") // outside reports/, so CI does not upload ~2000 PNGs
    fixturesDir.deleteRecursively()
    val fixtures = Fixtures.all()
    FixtureWriter.write(fixturesDir, fixtures)

    val metrics = ReplayRunner.run(fixturesDir, FixtureWriter.loadManifest(fixturesDir))
    val violations = thresholds.violations(metrics)
    writeMetrics(File(outDir, "metrics.json"), metrics, violations)
    writeEvidence(outDir, fixturesDir)

    metrics.forEach { (k, v) -> println("%-48s %10.3f".format(k, v)) }
    println(
        "${metrics.size} metrics, ${thresholds.size} thresholds, ${violations.size} violations",
    )
    violations.forEach { println("THRESHOLD VIOLATION: $it") }
    println("Wrote ${File(outDir, "metrics.json").path}")
    if (violations.isNotEmpty()) exitProcess(1)
}

private fun writeMetrics(file: File, metrics: Map<String, Double>, violations: List<String>) {
    val doc = buildJsonObject {
        put("schemaVersion", 1)
        put("source", "synthetic fixtures, seed ${Fixtures.DEFAULT_SEED}, baseline detectors")
        put("metrics", buildJsonObject { metrics.forEach { (k, v) -> put(k, v) } })
        put("violations", JsonArray(violations.map(::JsonPrimitive)))
    }
    file.writeText(PRETTY.encodeToString(JsonObject.serializer(), doc) + "\n")
}

private fun writeEvidence(outDir: File, fixturesDir: File) {
    val race = File(fixturesDir, "race_synth")
    val labels = Labels.load(File(race, Labels.FILE_NAME))
    val frames = ReplayRunner.loadFrames(race, labels)
    val step = frames.size / SHEET_FRAMES
    val highlight = labels.crossings.map { it.frame }.toSet()
    // An even sample of the race, plus every labelled crossing frame (outlined red).
    val shown = ((0 until SHEET_FRAMES).map { it * step } + highlight).distinct().sorted()
    ImageIO.write(
        EvidenceImages.contactSheet(frames, shown, SHEET_COLUMNS, highlight),
        "png",
        File(outDir, "contact-sheet.png"),
    )
    val bell = File(fixturesDir, "bell_snr10_distractors")
    val bellLabels = Labels.load(File(bell, Labels.FILE_NAME))
    val wav = WavIo.read(File(bell, requireNotNull(bellLabels.audioFile)))
    ImageIO.write(EvidenceImages.spectrogram(wav.samples, wav.sampleRate), "png", File(outDir, "spectrogram.png"))
}
