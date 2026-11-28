package net.belllap.bugreport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.belllap.audio.source.WavIo
import net.belllap.core.model.CrossingSource
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.testing.fakebackend.FakeBackend
import net.belllap.ui.BellUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BugReportControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakeBackend
    private var configured = true
    private var scheduled = 0
    private val trimmed = mutableListOf<TrimWindow>()

    private val bell = object : BellClipRecorder {
        override suspend fun record(out: File, maxMillis: Long): Long {
            WavIo.write(out, 16_000, ShortArray(16_000 * 2) { 3 })
            return 2_000
        }

        override fun stop() = Unit
    }

    private val trimmer = object : VideoTrimmer {
        override suspend fun durationUs(source: File) = 60_000_000L

        override suspend fun trim(source: File, window: TrimWindow, out: File): TrimWindow {
            trimmed += window
            out.writeBytes(ByteArray(5_000) { 9 })
            return window
        }
    }

    private val bundler = TraceBundler { dir ->
        listOf(
            Attachment(
                "trace-1.zip",
                AttachmentKind.TRACE,
                "application/zip",
                File(dir, "trace-1.zip").apply { writeText("zip") },
            ),
            Attachment(
                "capabilities.json",
                AttachmentKind.CAPABILITIES,
                "application/json",
                File(dir, "c.json").apply {
                    writeText("{}")
                },
            ),
        )
    }

    @Before
    fun setUp() {
        fake = FakeBackend()
    }

    @After
    fun tearDown() = fake.close()

    private fun video(name: String): CapturedVideo {
        val mp4 = tmp.newFile("$name.mp4").apply { writeBytes(ByteArray(10_000)) }
        val sidecar = Sidecar(
            id = name,
            name = name,
            videoFile = mp4.name,
            device = SidecarDevice("Google", "Pixel", 36),
            fps = 30,
            width = 1920,
            height = 1080,
            firstFramePtsUs = 1_000_000,
            crossings = listOf(SidecarCrossing(31_000_000, 900, 0.9f, CrossingSource.LIVE)),
            appVersion = "0.1.0",
        )
        return CapturedVideo(name, mp4, sidecar)
    }

    private fun TestScope.controller(vararg videos: CapturedVideo): Pair<BugReportController, ReportQueue> {
        val queue =
            ReportQueue(ReportStore(tmp.newFolder()), HttpReportApi(fake.baseUrl), { FakeBackend.VALID_TOKEN }, { 1L })
        val c = BugReportController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            bell = bell,
            trimmer = trimmer,
            bundler = bundler,
            queue = queue,
            scratch = File(tmp.root, "scratch"),
            captured = videos.toList(),
            reportContext = { ReportContext("6f1c2a9e-0b7d-4e8f-9a3c-1d2e3f4a5b6c", "0.1.0 (1)", "Test") },
            configured = { configured },
            schedule = { scheduled++ },
            bellAvailable = true,
            io = StandardTestDispatcher(testScheduler),
        )
        return c to queue
    }

    @Test
    fun nothingIsSentUntilConsentAndSend() = runTest {
        val (c, queue) = controller(video("Finish 1"))
        c.setDescription("Bell late on lap 12")
        c.startBell()
        advanceUntilIdle()
        assertEquals(BellUi.Recorded(2f), c.ui.value.form.bell)
        c.toggleVideo("Finish 1")
        advanceUntilIdle()
        assertEquals(30_000_000L, c.ui.value.form.videos.single().centerUs)

        c.review()
        advanceUntilIdle()

        val consent = c.ui.value.consent!!
        assertEquals(
            listOf("trace-1.zip", "capabilities.json", "bell.wav", "clip-1.mp4"),
            consent.items.map { it.name },
        )
        assertTrue(consent.hasVideo)
        assertEquals(listOf(TrimWindow(25_000_000, 35_000_000)), trimmed)
        assertTrue(fake.requests.isEmpty())
        assertTrue(queue.reports.value.isEmpty())

        c.send()
        c.ui.first { it.step == ReportStep.STATUS }
        assertEquals(1, scheduled)
        assertEquals(ProcessResult.DONE, queue.process())

        assertEquals(
            listOf("trace-1.zip", "capabilities.json", "bell.wav", "clip-1.mp4"),
            fake.issues.single().attachmentNames,
        )
        assertEquals("", c.ui.value.form.description)
        assertEquals(BellUi.None, c.ui.value.form.bell)
        assertFalse(File(tmp.root, "scratch/bell.wav").exists())
    }

    @Test
    fun backKeepsTheFormAndBlankDescriptionIsRejected() = runTest {
        val (c, _) = controller()
        c.review()
        advanceUntilIdle()
        assertEquals(listOf("Describe the problem."), c.ui.value.form.problems)
        assertEquals(ReportStep.FORM, c.ui.value.step)

        c.setDescription("Crash on standby")
        c.review()
        advanceUntilIdle()
        assertEquals(ReportStep.CONSENT, c.ui.value.step)
        c.back()
        assertEquals(ReportStep.FORM, c.ui.value.step)
        assertEquals("Crash on standby", c.ui.value.form.description)
    }

    @Test
    fun sendDoesNothingWhenNotConfigured() = runTest {
        configured = false
        val (c, queue) = controller()
        c.setDescription("x")
        c.review()
        advanceUntilIdle()
        assertFalse(c.ui.value.consent!!.configured)

        c.send()
        advanceUntilIdle()

        assertEquals(ReportStep.CONSENT, c.ui.value.step)
        assertTrue(queue.reports.value.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun atMostTwoVideosCanBeSelected() = runTest {
        val (c, _) = controller(video("a"), video("b"), video("c"))
        advanceUntilIdle()
        listOf("a", "b", "c").forEach(c::toggleVideo)
        assertEquals(listOf(true, true, false), c.ui.value.form.videos.map { it.selected })
    }
}
