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
import net.belllap.vision.flag.FlagConfig
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val SHEET_COLUMNS = 6
private const val DEFAULT_AMBIENCE_HOURS = 6
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
    val stage1 = Stage1.fromSystemProperties()
    val flagConfig = flagConfigFromSystemProperties()
    val metrics = ReplayRunner.run(root, names, stage1, flagConfig)
    println("bell stage 1: ${stage1.label}; flag config: $flagConfig")
    if (stage1 is Stage1.Dump) return
    File(outDir, "real-flag-trace.csv").writeText(
        (listOf("fixture,frame,pts_ms,visible,box,texture_box") + ReplayRunner.flagTrace)
            .joinToString("\n", postfix = "\n"),
    )
    File(outDir, "real-bell-trace.csv").writeText(
        (listOf("fixture,event,pts_ms,detail") + ReplayRunner.bellTrace).joinToString("\n", postfix = "\n"),
    )
    file.writeText(
        PRETTY.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", 1)
                put("status", "ran")
                put("bellStage1", stage1.label)
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
 * Writes `metrics.json`, `bell-trace.csv`, `contact-sheet.png` and `spectrogram.png`, and exits 1 if any threshold
 * is violated.
 */
fun main(args: Array<String>) {
    val outDir = File(args[0]).also { it.mkdirs() }
    val thresholds = Thresholds.load(File(args[1]))
    val fixturesDir = File(outDir.parentFile, "replay-fixtures") // outside reports/, so CI does not upload ~2000 PNGs
    fixturesDir.deleteRecursively()
    val fixtures = Fixtures.all()
    FixtureWriter.write(fixturesDir, fixtures)

    val stage1 = Stage1.fromSystemProperties()
    val metrics = ReplayRunner.run(fixturesDir, FixtureWriter.loadManifest(fixturesDir), stage1).toSortedMap()
    metrics += ReplayRunner.bellTotals(metrics)
    val hours = System.getProperty("belllap.bell.ambienceHours")?.toIntOrNull() ?: DEFAULT_AMBIENCE_HOURS
    val ambience = AmbienceReplay.run(hours, stage1, Fixtures.DEFAULT_SEED)
    metrics["ambience.bell.hours"] = ambience.hours.toDouble()
    metrics["ambience.bell.false_alarms_per_hour"] = ambience.falseAlarms.toDouble() / ambience.hours
    metrics["ambience.bell.candidates_per_hour"] = ambience.candidates.toDouble() / ambience.hours
    metrics["ambience.bell.distractors"] = ambience.distractors.values.sum().toDouble()
    println("bell stage 1: ${stage1.label}; ambience distractors ${ambience.distractors}")
    if (stage1 is Stage1.Dump) {
        println("Dumped candidate windows to ${stage1.dir.path}. Metrics from a dump run are not meaningful.")
        return
    }
    val violations = thresholds.violations(metrics)
    // The YAMNet run (scripts/bell/replay-yamnet.sh) reports beside the default run instead of replacing it.
    val suffix = if (stage1 is Stage1.Precomputed) "-yamnet" else ""
    val metricsFile = File(outDir, "metrics$suffix.json")
    writeMetrics(metricsFile, metrics, violations, stage1)
    writeEvidence(outDir, fixturesDir)
    File(outDir, "bell-trace$suffix.csv").writeText(
        (listOf("fixture,event,pts_ms,detail") + ReplayRunner.bellTrace).joinToString("\n", postfix = "\n"),
    )

    metrics.forEach { (k, v) -> println("%-48s %10.3f".format(k, v)) }
    println(
        "${metrics.size} metrics, ${thresholds.size} thresholds, ${violations.size} violations",
    )
    violations.forEach { println("THRESHOLD VIOLATION: $it") }
    println("Wrote ${metricsFile.path}")
    if (violations.isNotEmpty()) exitProcess(1)
}

private fun writeMetrics(file: File, metrics: Map<String, Double>, violations: List<String>, stage1: Stage1) {
    val doc = buildJsonObject {
        put("schemaVersion", 1)
        put("source", "synthetic fixtures, seed ${Fixtures.DEFAULT_SEED}; bell cascade, flag F16, crossings F18")
        put("bellStage1", stage1.label)
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
    writeFlagOverlays(outDir, fixturesDir)
    writeCrossingStrip(outDir, fixturesDir)
    val bell = File(fixturesDir, "bell_snr10_distractors")
    val bellLabels = Labels.load(File(bell, Labels.FILE_NAME))
    val wav = WavIo.read(File(bell, requireNotNull(bellLabels.audioFile)))
    ImageIO.write(EvidenceImages.spectrogram(wav.samples, wav.sampleRate), "png", File(outDir, "spectrogram.png"))
}

/** `flag-hit.png`: a frame mid-wave with the box. `flag-banner-rejected.png`: the banner, seen and rejected. */
private fun writeFlagOverlays(outDir: File, fixturesDir: File) {
    for ((fixture, file, atS) in listOf(
        Triple("flag", "flag-hit.png", FLAG_HIT_S),
        Triple("banner", "flag-banner-rejected.png", BANNER_S),
    )) {
        val dir = File(fixturesDir, fixture)
        val labels = Labels.load(File(dir, Labels.FILE_NAME))
        val frames = ReplayRunner.loadFrames(dir, labels)
        val run = FlagRun.over(frames)
        val i = (atS * requireNotNull(labels.fps)).toInt()
        ImageIO.write(FlagOverlay.render(frames[i], run.results[i]), "png", File(outDir, file))
    }
}

/** `crossings-strip.png`: the slit-scan strip of the `crossings` fixture, detected onsets red, labels yellow. */
private fun writeCrossingStrip(outDir: File, fixturesDir: File) {
    val dir = File(fixturesDir, "crossings")
    val labels = Labels.load(File(dir, Labels.FILE_NAME))
    val run = CrossingRun.over(ReplayRunner.loadFrames(dir, labels))
    val image = StripImage.render(run.strips, run.live, labels.crossings.map { it.frame })
    ImageIO.write(image, "png", File(outDir, "crossings-strip.png"))
}

private const val FLAG_HIT_S = 6.0
private const val BANNER_S = 6.0

/**
 * Overrides of the F16 detector defaults for a real-footage sweep, e.g.
 * `./gradlew :testing:harness:replayReal -Pflag.analysisWidth=640 -Pflag.cornerContrast=40`. Report only: the
 * shipped defaults stay in [FlagConfig].
 */
fun flagConfigFromSystemProperties(): FlagConfig {
    val d = FlagConfig()
    fun int(key: String) = System.getProperty("belllap.flag.$key")?.takeIf { it.isNotBlank() }?.toInt()
    return d.copy(
        analysisWidth = int("analysisWidth") ?: d.analysisWidth,
        cornerContrast = int("cornerContrast") ?: d.cornerContrast,
    )
}
