package net.belllap.testing.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class RaceSynthMediaTest {
    @Test
    fun labelsWithoutFramesMatchTheFullFixture() {
        val full = Fixtures.race()
        val labelsOnly = Fixtures.race(withFrames = false)
        assertEquals(full.labels, labelsOnly.labels)
        assertEquals(null, labelsOnly.frames)
    }

    @Test
    fun encodesEveryFrameAsBaselineH264() {
        assumeTrue("ffmpeg and ffprobe are not on PATH", onPath("ffmpeg") && onPath("ffprobe"))
        val dir = createTempDirectory("race_synth").toFile()
        RaceSynthMedia.write(dir)
        val fixture = Fixtures.race()
        val first = checkNotNull(fixture.frames).first()
        val probe = run(
            "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height,nb_read_frames,has_b_frames", "-of", "compact=nk=0:p=0",
            File(dir, "race_synth.mp4").absolutePath,
        )
        assertEquals(
            "codec_name=h264|width=${first.width}|height=${first.height}|has_b_frames=0|" +
                "nb_read_frames=${fixture.frames.size}",
            probe.trim(),
        )
        assertEquals(true, File(dir, "race_synth.wav").length() > 0)
    }

    private fun onPath(tool: String) = ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() == 0

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(1, TimeUnit.MINUTES) && p.exitValue() == 0) { out }
        return out
    }
}
