package net.belllap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import net.belllap.bugreport.AttachmentKind
import net.belllap.bugreport.IssueRef
import net.belllap.bugreport.QueuedReport
import net.belllap.bugreport.ReportContext
import net.belllap.bugreport.ReportState
import net.belllap.bugreport.StoredAttachment
import net.belllap.bugreport.UploadProgress
import net.belllap.core.model.CaptureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class BugReportScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val candidates = listOf(CandidateUi("Crossing 1", 30_000_000), CandidateUi("Flag 1 seen", 22_000_000))
    private val videos = listOf(
        VideoRowUi("2026-11-28 Finish 1", "48.2 MB", true, 30_000_000, 95_000_000, candidates),
        VideoRowUi("2026-11-28 Finish 2", "51.0 MB", false, 0, 80_000_000, emptyList()),
    )

    @Composable
    private fun Form(
        ui: BugReportFormUi,
        onDescription: (String) -> Unit = {},
        onContinue: () -> Unit = {},
        onRecord: () -> Unit = {},
        onToggle: (String) -> Unit = {},
    ) = BugReportScreen(
        ui = ui,
        onDescriptionChange = onDescription,
        onRecordBell = onRecord,
        onStopBell = {},
        onClearBell = {},
        onToggleVideo = onToggle,
        onCenterChange = { _, _ -> },
        onContinue = onContinue,
        onOpenQueue = {},
        onCancel = {},
    )

    @Test
    fun emptyFormDisablesContinue() {
        composeRule.setContent { Form(BugReportFormUi(videos = videos.map { it.copy(selected = false) })) }

        composeRule.onNodeWithTag(TestTags.BUG_BELL_RECORD).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.BUG_CONTINUE).assertIsNotEnabled()
        composeRule.onNodeWithTag(
            TestTags.BUG_FORM_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_form_empty.png")
    }

    @Test
    fun formWithBellAndVideoEnablesContinue() {
        var continued = 0
        composeRule.setContent {
            Form(
                BugReportFormUi("Bell detected late on lap 12", BellUi.Recorded(7.4f), true, videos, queuedCount = 1),
                onContinue = { continued++ },
            )
        }

        composeRule.onNodeWithTag(TestTags.BUG_BELL_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.BUG_VIDEO_SLIDER_PREFIX + "2026-11-28 Finish 1").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.BUG_CONTINUE).assertIsEnabled().performClick()
        assertEquals(1, continued)
        composeRule.onNodeWithTag(
            TestTags.BUG_FORM_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_form_bell_video.png")
    }

    @Test
    fun bellIsNotOfferedWhileASessionRuns() {
        composeRule.setContent { Form(BugReportFormUi(bellAvailable = false)) }
        composeRule.onNodeWithTag(TestTags.BUG_BELL_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.BUG_BELL_RECORD).assertDoesNotExist()
    }

    private val items = listOf(
        ConsentItemUi("trace-20261128-101500.zip", AttachmentKind.TRACE, 812_000),
        ConsentItemUi("capabilities.json", AttachmentKind.CAPABILITIES, 2_311),
        ConsentItemUi("bell.wav", AttachmentKind.BELL, 236_000),
        ConsentItemUi("clip-1.mp4", AttachmentKind.VIDEO, 18_400_000),
    )

    @Test
    fun consentListsEverythingAndSendsOnlyOnTap() {
        var sent = 0
        composeRule.setContent {
            BugReportConsentScreen(
                BugReportConsentUi("Bell detected late on lap 12", items, configured = true),
                onSend = { sent++ },
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(TestTags.CONSENT_ITEM_PREFIX + "clip-1.mp4").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.CONSENT_VIDEO_WARNING).assertIsDisplayed()
        assertEquals(0, sent)
        composeRule.onNodeWithTag(TestTags.CONSENT_SEND).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, sent)
        composeRule.onNodeWithTag(
            TestTags.CONSENT_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_consent.png")
    }

    @Test
    fun consentDisablesSendWhenNotConfigured() {
        composeRule.setContent {
            BugReportConsentScreen(BugReportConsentUi("d", items.take(2), configured = false), onSend = {}, onBack = {})
        }

        composeRule.onNodeWithTag(TestTags.CONSENT_NOT_CONFIGURED).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.CONSENT_SEND).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(TestTags.CONSENT_VIDEO_WARNING).assertDoesNotExist()
        composeRule.onNodeWithTag(
            TestTags.CONSENT_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_consent_not_configured.png")
    }

    private fun report(id: String, state: ReportState) = QueuedReport(
        localId = id,
        createdAtMs = 1,
        context = ReportContext("6f1c2a9e-0b7d-4e8f-9a3c-1d2e3f4a5b6c", "0.1.0 (1)", "Test"),
        description = "Bell detected late on lap 12",
        attachments = listOf(StoredAttachment("trace.zip", AttachmentKind.TRACE, "application/zip", 812_000)),
        state = state,
    )

    @Composable
    private fun Status(vararg rows: ReportRowUi, onRetry: (String) -> Unit = {}, onDiscard: (String) -> Unit = {}) =
        BugReportStatusScreen(rows.toList(), onRetry, onDiscard, {}, {}, {})

    @Test
    fun uploadingShowsProgress() {
        val row = reportRow(report("a", ReportState.Pending), UploadProgress("clip-1.mp4", 7_300_000, 18_400_000))
        assertEquals(ReportStatus.UPLOADING, row.status)
        composeRule.setContent { Status(row) }

        composeRule.onNodeWithTag(TestTags.STATUS_ROW_PREFIX + "a").assertIsDisplayed()
        composeRule.onNodeWithTag(
            TestTags.STATUS_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_status_uploading.png")
    }

    @Test
    fun failedOfflineOffersRetryAndDiscard() {
        val failed = ReportState.Failed("no network", retryable = true, resumeFrom = ReportState.Pending)
        val row = reportRow(report("b", failed), null)
        var retried: String? = null
        var discarded: String? = null
        composeRule.setContent { Status(row, onRetry = { retried = it }, onDiscard = { discarded = it }) }

        composeRule.onNodeWithTag(TestTags.STATUS_RETRY_PREFIX + "b").performClick()
        composeRule.onNodeWithTag(TestTags.STATUS_DISCARD_PREFIX + "b").performClick()
        assertEquals("b", retried)
        assertEquals("b", discarded)
        assertTrue(row.detail.contains("retry when the network is back"))
        composeRule.onNodeWithTag(
            TestTags.STATUS_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_status_failed_offline.png")
    }

    @Test
    fun filedLinksToTheIssue() {
        val filed = ReportState.Filed(IssueRef(123, "https://github.com/surpdeh/belllap/issues/123"))
        val row = reportRow(report("c", filed), null)
        composeRule.setContent { Status(row) }

        composeRule.onNodeWithTag(TestTags.STATUS_ISSUE_PREFIX + "c").assertIsDisplayed()
        assertEquals("Filed as issue #123", row.detail)
        composeRule.onNodeWithTag(
            TestTags.STATUS_SCREEN,
        ).captureRoboImage("src/test/screenshots/bugreport_status_filed.png")
    }

    @Test
    fun setupScreenOffersReportAProblem() {
        var opened = 0
        composeRule.setContent {
            SetupScreen(
                settings = CaptureSettings(),
                capabilities = null,
                permission = PermissionUi.GRANTED,
                settingsOpen = false,
                onSettingsOpenChange = {},
                onSettingsChange = {},
                onFinishLineChange = {},
                onRequestPermissions = {},
                onOpenSystemSettings = {},
                onReportProblem = { opened++ },
                preview = { CameraPreviewPlaceholder() },
            )
        }

        composeRule.onNodeWithTag(TestTags.REPORT_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithTag(
            TestTags.SETUP_SCREEN,
        ).captureRoboImage("src/test/screenshots/setup_report_button.png")
    }
}
