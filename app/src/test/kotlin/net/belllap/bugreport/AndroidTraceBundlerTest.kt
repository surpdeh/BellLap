package net.belllap.bugreport

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class AndroidTraceBundlerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun bundlesLogsSidecarsAndLogcatButNeverVideo() = runTest {
        val logs = tmp.newFolder("logs").apply {
            resolve("events.jsonl").writeText("{}\n")
            resolve("events.1.jsonl").writeText("{}\n")
            resolve("belllap.log").writeText("log\n")
            resolve("device-capabilities.json").writeText("{\"cameras\":[]}")
            resolve("unrelated.bin").writeText("x")
        }
        val acq = tmp.newFolder("acquisitions").apply {
            resolve("acq_a.json").writeText("{}")
            resolve("acq_a.mp4").writeText("not a video")
        }
        val bundler = AndroidTraceBundler(logs, acq, nowMs = { 0L }, logcat = { "I/BellLap: hello" })

        val attachments = bundler.bundle(tmp.newFolder("out"))

        assertEquals(listOf(AttachmentKind.TRACE, AttachmentKind.CAPABILITIES), attachments.map { it.kind })
        assertEquals("capabilities.json", attachments[1].name)
        assertTrue(attachments[0].name.matches(Regex("trace-\\d{8}-\\d{6}\\.zip")))
        val names = ZipFile(attachments[0].file).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        assertEquals(setOf("events.jsonl", "events.1.jsonl", "belllap.log", "acq_a.json", "logcat.txt"), names)
        assertTrue(ReportDraft("d", attachments).validate().isEmpty())
    }

    @Test
    fun worksWithoutLogcatOrCapabilities() = runTest {
        val logs = tmp.newFolder("logs").apply { resolve("events.jsonl").writeText("{}\n") }
        val bundler = AndroidTraceBundler(logs, tmp.newFolder("acq"), logcat = { null })

        val attachments = bundler.bundle(tmp.newFolder("out"))

        assertEquals(listOf(AttachmentKind.TRACE), attachments.map { it.kind })
    }
}
