package net.belllap.testing.fixtures

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.belllap.audio.source.WavIo
import net.belllap.vision.core.LumaImages
import java.io.File

/**
 * Writes fixtures to disk: `<root>/<name>/{labels.json, <name>.wav, <framesDir>/frame_00000.png}`
 * and `<root>/manifest.json`.
 */
object FixtureWriter {
    const val MANIFEST = "manifest.json"
    private val PRETTY = Json { prettyPrint = true }

    fun write(root: File, fixtures: List<Fixture>) {
        fixtures.forEach { writeOne(File(root, it.labels.name), it) }
        root.mkdirs()
        File(root, MANIFEST).writeText(PRETTY.encodeToString(fixtures.map { it.labels.name }))
    }

    fun writeOne(dir: File, fixture: Fixture) {
        dir.mkdirs()
        val labels = fixture.labels
        fixture.audio?.let { WavIo.write(File(dir, requireNotNull(labels.audioFile)), it.sampleRate, it.samples) }
        fixture.frames?.forEachIndexed { i, f ->
            LumaImages.writePng(f, File(File(dir, requireNotNull(labels.framesDir)), "frame_%05d.png".format(i)))
        }
        File(dir, Labels.FILE_NAME).writeText(labels.encode())
    }

    fun loadManifest(root: File): List<String> = Json.decodeFromString(File(root, MANIFEST).readText())
}
