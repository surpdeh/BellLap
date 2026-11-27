package net.belllap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.rule.GrantPermissionRule
import net.belllap.ui.TestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToSetupScreen() {
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("SETUP")
        composeRule.waitForIdle()
        TestEvidence.awaitPreviewContent { timeoutMs, condition -> composeRule.waitUntil(timeoutMs, condition) }
        // Hold the screen briefly so the screen recording captures it.
        Thread.sleep(HOLD_MS)
        val shot = TestEvidence.screenshot("01-setup-launch")
        assertTrue("Screenshot missing: $shot", shot.length() > 0)
    }

    private companion object {
        const val HOLD_MS = 2_000L
    }
}
