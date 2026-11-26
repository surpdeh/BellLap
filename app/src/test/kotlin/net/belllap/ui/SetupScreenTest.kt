package net.belllap.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsSetupScreenWithStatusOverlay() {
        composeRule.setContent { SetupScreen() }

        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("SETUP")
        composeRule.onNodeWithTag(TestTags.CAMERA_PREVIEW_PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun setupScreenScreenshot() {
        composeRule.setContent { SetupScreen() }

        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_screen.png")
    }
}
