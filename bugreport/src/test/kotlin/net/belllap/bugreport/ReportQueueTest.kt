package net.belllap.bugreport

import kotlinx.coroutines.test.runTest
import net.belllap.testing.fakebackend.Endpoint
import net.belllap.testing.fakebackend.FakeBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val slept = mutableListOf<Long>()
    private lateinit var fake: FakeBackend
    private lateinit var root: java.io.File
    private val context = ReportContext("6f1c2a9e-0b7d-4e8f-9a3c-1d2e3f4a5b6c", "0.1.0", "Test / Android 16")
    private var token = AppCheckTokenSource { FakeBackend.VALID_TOKEN }

    @Before
    fun setUp() {
        fake = FakeBackend { now }
        root = tmp.newFolder("bugreports")
    }

    @After
    fun tearDown() = fake.close()

    private fun queue(maxRetries: Int = 8, advanceOnSleep: Long = 0) = ReportQueue(
        ReportStore(root),
        HttpReportApi(fake.baseUrl),
        { token.token() },
        { now },
        RetryPolicy(maxRetries) {
            slept += it
            now += advanceOnSleep
        },
    )

    private fun file(name: String, size: Int) =
        tmp.newFile("src-$name").apply { writeBytes(ByteArray(size) { (it % 7).toByte() }) }

    private fun draft(vararg names: String) = ReportDraft(
        "Bell detected late on lap 12",
        names.map { n ->
            val kind = if (n.endsWith(".json")) AttachmentKind.CAPABILITIES else AttachmentKind.TRACE
            val type = if (n.endsWith(".json")) "application/json" else "application/zip"
            Attachment(n, kind, type, file(n, 3000 + n.length))
        },
    )

    private suspend fun enqueue(q: ReportQueue, d: ReportDraft): QueuedReport =
        (q.enqueue(d, context) as EnqueueResult.Queued).report

    private fun puts() = fake.requests.filter { it.startsWith("PUT") }

    @Test
    fun happyPathFilesTheIssueAndFreesTheMedia() = runTest {
        val q = queue()
        val queued = enqueue(q, draft("capabilities.json", "trace-1.zip"))

        assertEquals(ProcessResult.DONE, q.process())

        val state = q.reports.value.single().state as ReportState.Filed
        assertEquals(fake.issues.single().url, state.issue.url)
        assertEquals(listOf("capabilities.json", "trace-1.zip"), fake.issues.single().attachmentNames)
        val stored = fake.stored(fake.reportIds().single())
        assertEquals(mapOf("capabilities.json" to 3017L, "trace-1.zip" to 3011L), stored)
        val remaining = ReportStore(root).dir(queued.localId).list().orEmpty().toList()
        assertEquals(listOf(ReportStore.FILE), remaining)
        assertTrue(q.progress.value.isEmpty())
    }

    @Test
    fun offlineThenOnlineConvergesWithBackoff() = runTest {
        val q = queue(maxRetries = 2)
        enqueue(q, draft("capabilities.json"))
        fake.online = false

        assertEquals(ProcessResult.RETRY_LATER, q.process())
        val failed = q.reports.value.single().state as ReportState.Failed
        assertTrue(failed.retryable)
        assertEquals(ReportState.Pending, failed.resumeFrom)
        assertEquals(listOf(2_000L, 4_000L), slept)

        fake.online = true
        assertEquals(ProcessResult.DONE, q.process())
        assertTrue(q.reports.value.single().state is ReportState.Filed)
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun midUploadFailureResumesWithoutResendingFinishedFiles() = runTest {
        val q = queue()
        enqueue(q, draft("a.zip", "b.zip"))
        fake.dropNextPutAfter(100)

        // The first PUT (a.zip) is the one that is cut.
        assertEquals(ProcessResult.DONE, q.process())

        assertEquals(1, fake.reportIds().size)
        assertEquals(2, puts().count { it.endsWith("a.zip") })
        assertEquals(1, puts().count { it.endsWith("b.zip") })
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun restartResumesFromThePersistedStateWithoutCreatingAgain() = runTest {
        val first = queue(maxRetries = 0)
        val queued = enqueue(first, draft("a.zip", "b.zip"))
        fake.dropNextPutAfter(1) // no script for a.zip's success: the first PUT is cut and the budget is zero
        assertEquals(ProcessResult.RETRY_LATER, first.process())
        val created = (ReportStore(root).load().single().state as ReportState.Failed).resumeFrom as ReportState.Created

        val restarted = queue()
        assertEquals(queued.localId, restarted.reports.value.single().localId)
        assertEquals(ProcessResult.DONE, restarted.process())

        assertEquals(listOf(created.reportId), fake.reportIds())
        assertEquals(1, fake.requests.count { it == "POST /v1/reports" })
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun expiredUrlsStartANewReport() = runTest {
        fake.urlTtlMs = 1_000
        val q = queue(advanceOnSleep = 10_000)
        enqueue(q, draft("a.zip"))
        fake.dropNextPutAfter(10)

        assertEquals(ProcessResult.DONE, q.process())

        assertEquals(2, fake.reportIds().size)
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun uploadsMissingReuploadsTheNamedFilesOnly() = runTest {
        val q = queue()
        enqueue(q, draft("a.zip", "b.zip"))
        fake.failNext(Endpoint.SUBMIT, 409, """{"error":"uploads_missing","missing":["b.zip"]}""")

        assertEquals(ProcessResult.DONE, q.process())

        assertEquals(1, puts().count { it.endsWith("a.zip") })
        assertEquals(2, puts().count { it.endsWith("b.zip") })
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun sizeMismatchReuploadsWhileTheUrlIsValid() = runTest {
        val q = queue()
        enqueue(q, draft("a.zip"))
        fake.failNext(Endpoint.SUBMIT, 422, """{"error":"size_mismatch","names":["a.zip"]}""")

        assertEquals(ProcessResult.DONE, q.process())

        assertEquals(2, puts().size)
        assertEquals(1, fake.reportIds().size)
    }

    @Test
    fun inProgressRateLimitAndGithubFailureBackOffThenSucceed() = runTest {
        val q = queue()
        enqueue(q, draft("a.zip"))
        fake.failNext(Endpoint.CREATE, 429, """{"error":"rate_limited"}""")
        fake.failNext(Endpoint.SUBMIT, 409, """{"error":"in_progress"}""")
        fake.failNext(Endpoint.SUBMIT, 502, """{"error":"github_failed"}""")

        assertEquals(ProcessResult.DONE, q.process())

        assertEquals(listOf(60_000L, 5_000L, 8_000L), slept)
        assertEquals(1, fake.issues.size)
    }

    @Test
    fun repeatSubmitReturnsTheSameIssue() = runTest {
        val q = queue()
        enqueue(q, draft("a.zip"))
        q.process()
        val api = HttpReportApi(fake.baseUrl)
        val id = fake.reportIds().single()

        val again = api.submit(FakeBackend.VALID_TOKEN, id)

        assertEquals(SubmitResult.Filed(IssueRef(1, fake.issues.single().url)), again)
        assertEquals(1, fake.issues.size)
        assertEquals(ProcessResult.DONE, q.process())
    }

    @Test
    fun rejectedAppCheckIsTerminalUntilTheUserRetries() = runTest {
        token = AppCheckTokenSource { "wrong" }
        val q = queue()
        val queued = enqueue(q, draft("a.zip"))

        assertEquals(ProcessResult.DONE, q.process())
        val failed = q.reports.value.single().state as ReportState.Failed
        assertFalse(failed.retryable)
        assertTrue(failed.message.contains("App Check"))
        assertTrue(q.reports.value.single().isFinished)

        token = AppCheckTokenSource { FakeBackend.VALID_TOKEN }
        q.retry(queued.localId)
        assertEquals(ProcessResult.DONE, q.process())
        assertTrue(q.reports.value.single().state is ReportState.Filed)
    }

    @Test
    fun invalidDraftCannotBeQueued() = runTest {
        val q = queue()
        val result = q.enqueue(ReportDraft(" ", emptyList()), context)
        assertEquals(EnqueueResult.Invalid(listOf(DraftProblem.EmptyDescription)), result)
        assertTrue(q.reports.value.isEmpty())
    }

    @Test
    fun discardRemovesTheReportAndItsFiles() = runTest {
        val q = queue()
        val queued = enqueue(q, draft("a.zip"))
        q.discard(queued.localId)
        assertTrue(q.reports.value.isEmpty())
        assertFalse(ReportStore(root).dir(queued.localId).exists())
        assertTrue(ReportStore(root).load().isEmpty())
    }
}
