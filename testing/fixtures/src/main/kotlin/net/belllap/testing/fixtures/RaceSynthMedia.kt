package net.belllap.testing.fixtures

import net.belllap.audio.source.WavIo
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Writes `race_synth.wav` and `race_synth.mp4` (H.264, encoded by the host's ffmpeg) for the emulator replay
 * tests, so the slow on-device encode is skipped. ffmpeg runs only on the build machine and is never shipped.
 */
object RaceSynthMedia {
    const val NAME = "race_synth"
    private const val FFMPEG_TIMEOUT_MIN = 5L

    fun write(dir: File, ffmpeg: String = "ffmpeg") {
        val fixture = Fixtures.race()
        val frames = checkNotNull(fixture.frames)
        val audio = checkNotNull(fixture.audio)
        val first = frames.first()
        dir.mkdirs()
        WavIo.write(File(dir, "$NAME.wav"), audio.sampleRate, audio.samples)
        val mp4 = File(dir, "$NAME.mp4")
        val fps = checkNotNull(fixture.labels.fps)
        // Baseline profile and no B-frames: the emulator's goldfish decoder handles it, and decode order is PTS order.
        val process = ProcessBuilder(
            ffmpeg, "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "gray", "-s", "${first.width}x${first.height}", "-r", fps.toString(),
            "-i", "-",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
            "-bf", "0", "-g", fps.toInt().toString(),
            "-threads", "1", "-map_metadata", "-1", "-fflags", "+bitexact",
            mp4.absolutePath,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.buffered().use { out ->
            frames.forEach { f ->
                for (row in 0 until f.height) out.write(f.luma, row * f.stride, f.width)
            }
        }
        check(process.waitFor(FFMPEG_TIMEOUT_MIN, TimeUnit.MINUTES)) { "ffmpeg did not finish" }
        check(process.exitValue() == 0 && mp4.length() > 0) { "ffmpeg failed with exit code ${process.exitValue()}" }
    }
}

/** `main(outDir)`: see [RaceSynthMedia]. */
fun main(args: Array<String>) {
    val dir = File(requireNotNull(args.firstOrNull()) { "usage: RaceSynthMediaKt <output dir>" })
    RaceSynthMedia.write(dir)
    println("Wrote ${RaceSynthMedia.NAME}.mp4 and .wav to ${dir.absolutePath}")
}
