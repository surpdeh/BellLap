package net.belllap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import net.belllap.core.statemachine.State
import net.belllap.ui.TestTags
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** F14 on a real OS: SETUP, Standby, the big Bell now button, ARMING, then Stop back to SETUP. */
class StandbyBellNowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** The session is process-wide, so leave it in SETUP for the next test even if this one fails. */
    @After
    fun backToSetup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BellLapApp
        if (app.session.state.value != State.Setup) app.session.stop()
    }

    @Test
    fun bellNowArmsFromStandby() {
        composeRule.onNodeWithTag(TestTags.STANDBY_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.STANDBY_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("STANDBY")
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).assertIsDisplayed()
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MS)
        TestEvidence.screenshot("02-standby")

        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("ARMING")
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).assertDoesNotExist()
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MS)
        TestEvidence.screenshot("03-arming")

        composeRule.onNodeWithTag(TestTags.STOP_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("SETUP")
    }

    private companion object {
        const val HOLD_MS = 1_500L
    }
}
