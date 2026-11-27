package net.belllap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.OutputSize
import org.junit.Assert.assertEquals
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

    // 30 fps only, 720p and 1080p only: the 60 fps and 2160p chips must be disabled.
    private val caps = CameraCapabilities(
        outputSizes = listOf(OutputSize(1280, 720), OutputSize(1920, 1080)),
        fpsRanges = listOf(15..30),
    )

    @Composable
    private fun Screen(
        permission: PermissionUi = PermissionUi.GRANTED,
        settingsOpen: Boolean = false,
        settings: CaptureSettings = CaptureSettings(),
        onChange: (CaptureSettings) -> Unit = {},
        onStandby: () -> Unit = {},
    ) = SetupScreen(
        settings = settings,
        capabilities = caps,
        permission = permission,
        settingsOpen = settingsOpen,
        onSettingsOpenChange = {},
        onSettingsChange = onChange,
        onRequestPermissions = {},
        onOpenSystemSettings = {},
        onStandby = onStandby,
        preview = { CameraPreviewPlaceholder() },
    )

    @Test
    fun showsSetupScreenWithStatusOverlay() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextEquals("SETUP")
        composeRule.onNodeWithTag(TestTags.CAMERA_PREVIEW_PLACEHOLDER).assertIsDisplayed()
    }

    @Test
    fun settingsOverlayDisablesUnsupportedOptions() {
        composeRule.setContent { Screen(settingsOpen = true) }

        composeRule.onNodeWithTag(TestTags.SETTINGS_OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag("chip_60 fps").assertIsNotEnabled()
        composeRule.onNodeWithTag("chip_2160p").assertIsNotEnabled()
    }

    @Test
    fun togglingAutomationEmitsUpdatedSettings() {
        var latest: CaptureSettings? = null
        composeRule.setContent { Screen(settingsOpen = true, onChange = { latest = it }) }

        composeRule.onNodeWithTag(TestTags.TOGGLE_BELL).performClick()
        assertEquals(CaptureSettings(bellAutomation = false), latest)
        composeRule.onNodeWithTag(TestTags.TOGGLE_VOLUME).performScrollTo().performClick()
        assertEquals(80, latest?.alertVolumePercent)
    }

    @Test
    fun bellSensitivityChipsSetTheSettingAndFollowTheBellToggle() {
        var latest: CaptureSettings? = null
        composeRule.setContent { Screen(settingsOpen = true, onChange = { latest = it }) }
        composeRule.onNodeWithTag("chip_High").performScrollTo().performClick()
        assertEquals(BellSensitivity.HIGH, latest?.bellSensitivity)
    }

    @Test
    fun sensitivityChipsAreDisabledWhenBellDetectionIsOff() {
        composeRule.setContent { Screen(settingsOpen = true, settings = CaptureSettings(bellAutomation = false)) }
        composeRule.onNodeWithTag("chip_Low").assertIsNotEnabled()
    }

    @Test
    fun standbyButtonStartsStandby() {
        var standby = 0
        composeRule.setContent { Screen(onStandby = { standby++ }) }
        composeRule.onNodeWithTag(TestTags.STANDBY_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, standby)
    }

    @Test
    fun permissionRationaleReplacesPreviewUntilGranted() {
        composeRule.setContent { Screen(permission = PermissionUi.RATIONALE) }

        composeRule.onNodeWithTag(TestTags.PERMISSION_RATIONALE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.GRANT_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.SETTINGS_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.STANDBY_BUTTON).assertDoesNotExist()
    }

    @Test
    fun blockedPermissionOffersSystemSettings() {
        composeRule.setContent { Screen(permission = PermissionUi.BLOCKED) }

        composeRule.onNodeWithTag(TestTags.OPEN_SETTINGS_BUTTON).assertIsDisplayed()
    }

    @Test
    fun permissionUiMapping() {
        assertEquals(PermissionUi.GRANTED, permissionUi(true, true, false))
        assertEquals(PermissionUi.RATIONALE, permissionUi(false, false, false))
        assertEquals(PermissionUi.RATIONALE, permissionUi(false, true, true))
        assertEquals(PermissionUi.BLOCKED, permissionUi(false, true, false))
    }

    @Test
    fun setupScreenScreenshot() {
        composeRule.setContent { Screen() }
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_screen.png")
    }

    @Test
    fun settingsOverlayScreenshot() {
        composeRule.setContent { Screen(settingsOpen = true, settings = CaptureSettings(alertVolumePercent = 70)) }
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_settings_overlay.png")
    }

    @Test
    fun permissionRationaleScreenshot() {
        composeRule.setContent { Screen(permission = PermissionUi.RATIONALE) }
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_permission_rationale.png")
    }
}
