package net.belllap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import net.belllap.bugreport.HttpReportApi
import net.belllap.bugreport.ProcessResult
import net.belllap.bugreport.ReportOverrides
import net.belllap.bugreport.ReportState
import net.belllap.testing.fakebackend.FakeBackend
import net.belllap.ui.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * F22 on a real OS, against an in-process fake of the F23 Worker: open the form from SETUP, describe the problem,
 * pass the consent screen and see the issue filed. The trace bundle is built from this app's own logs.
 */
class BugReportFlowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BellLapApp
    private lateinit var backend: FakeBackend

    @Before
    fun injectFakeBackend() {
        backend = FakeBackend()
        ReportOverrides.api = HttpReportApi(backend.baseUrl)
        ReportOverrides.tokens = { FakeBackend.VALID_TOKEN }
    }

    @After
    fun restore() {
        ReportOverrides.api = null
        ReportOverrides.tokens = null
        backend.close()
        File(app.filesDir, "bugreports").deleteRecursively()
    }

    @Test
    fun describeConsentSendFilesTheIssue() {
        composeRule.onNodeWithTag(TestTags.REPORT_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.BUG_FORM_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.BUG_DESCRIPTION).performTextInput(DESCRIPTION)
        composeRule.waitForIdle()
        TestEvidence.screenshot("bugreport-1-form")

        composeRule.onNodeWithTag(TestTags.BUG_CONTINUE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) { nodeExists(TestTags.CONSENT_SCREEN) }
        composeRule.onNodeWithTag(TestTags.CONSENT_SEND).performScrollTo().assertIsDisplayed()
        assertTrue("nothing may be sent before Send", backend.requests.isEmpty())
        composeRule.waitForIdle()
        TestEvidence.screenshot("bugreport-2-consent")

        composeRule.onNodeWithTag(TestTags.CONSENT_SEND).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) { nodeExists(TestTags.STATUS_SCREEN) }
        // WorkManager also runs the queue once the network is up; process() is idempotent, so draining it here
        // keeps the test independent of that constraint.
        assertEquals(ProcessResult.DONE, runBlocking { app.bugReports.queue.process() })
        composeRule.waitUntil(TIMEOUT_MS) { app.bugReports.reports.value.all { it.state is ReportState.Filed } }
        composeRule.waitForIdle()
        TestEvidence.screenshot("bugreport-3-status")

        val issue = backend.issues.single()
        assertEquals(DESCRIPTION, issue.description)
        assertTrue(issue.attachmentNames.toString(), issue.attachmentNames.any { it.startsWith("trace-") })
        val state = app.bugReports.reports.value.single().state as ReportState.Filed
        assertEquals(issue.url, state.issue.url)
    }

    @Test
    fun backFromConsentSendsNothing() {
        composeRule.onNodeWithTag(TestTags.REPORT_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.BUG_DESCRIPTION).performTextInput(DESCRIPTION)
        composeRule.onNodeWithTag(TestTags.BUG_CONTINUE).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MS) { nodeExists(TestTags.CONSENT_SCREEN) }

        composeRule.onNodeWithTag(TestTags.CONSENT_BACK).performScrollTo().performClick()

        composeRule.onNodeWithTag(TestTags.BUG_FORM_SCREEN).assertIsDisplayed()
        assertTrue(backend.requests.isEmpty())
        assertTrue(app.bugReports.reports.value.isEmpty())
    }

    private fun nodeExists(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val DESCRIPTION = "Bell detected late on lap 12"
        const val TIMEOUT_MS = 30_000L
    }
}
