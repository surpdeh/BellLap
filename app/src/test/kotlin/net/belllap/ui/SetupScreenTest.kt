package net.belllap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.github.takahirom.roborazzi.captureRoboImage
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.core.model.OutputSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        onLine: (FinishLine?) -> Unit = {},
        onStandby: () -> Unit = {},
        facings: Set<CameraFacing>? = null,
    ) = SetupScreen(
        settings = settings,
        capabilities = caps,
        facings = facings,
        permission = permission,
        settingsOpen = settingsOpen,
        onSettingsOpenChange = {},
        onSettingsChange = onChange,
        onFinishLineChange = onLine,
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
    fun settingsOverlayOffersOnlyCamerasTheDeviceHas() {
        composeRule.setContent { Screen(settingsOpen = true, facings = setOf(CameraFacing.BACK)) }

        composeRule.onNodeWithTag("chip_Back").assertIsEnabled()
        composeRule.onNodeWithTag("chip_Front").assertIsNotEnabled()
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

    // The test window is 320x470 dp-ish; the 16:9 frame is letterboxed, so tests read the overlay size.
    private val stored = FinishLine(NormPoint(0.5f, 0.2f), NormPoint(0.5f, 0.8f))

    @Test
    fun drawingByDraggingEmitsNormalisedLine() {
        var latest: FinishLine? = null
        composeRule.setContent { Screen(onLine = { latest = it }) }
        composeRule.onNodeWithTag(TestTags.LINE_BUTTON).performClick()

        composeRule.onNodeWithTag(TestTags.LINE_EDITOR).performTouchInput {
            // Drag across the vertical middle of the frame; the frame fills the width.
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.5f, height * 0.7f), 200)
        }
        val line = latest!!
        assertEquals(0.5f, line.start.x, 0.02f)
        assertEquals(0.5f, line.end.x, 0.02f)
        assertTrue(line.start.y < line.end.y)
    }

    @Test
    fun draggingAnEndpointHandleMovesOnlyThatEndpoint() {
        var latest: FinishLine? = null
        composeRule.setContent { Screen(settings = CaptureSettings(finishLine = stored), onLine = { latest = it }) }
        composeRule.onNodeWithTag(TestTags.LINE_BUTTON).performClick()

        composeRule.onNodeWithTag(TestTags.LINE_EDITOR).performTouchInput {
            val frameH = width * 9f / 16f
            val top = (height - frameH) / 2f
            val handle = Offset(width * 0.5f, top + frameH * 0.2f)
            swipe(handle, Offset(width * 0.8f, handle.y), 200)
        }
        val line = latest!!
        assertEquals(0.8f, line.start.x, 0.03f)
        assertEquals(0.2f, line.start.y, 0.03f)
        assertEquals(stored.end, line.end)
    }

    @Test
    fun clearButtonRemovesTheLine() {
        var latest: FinishLine? = stored
        composeRule.setContent { Screen(settings = CaptureSettings(finishLine = stored), onLine = { latest = it }) }
        composeRule.onNodeWithTag(TestTags.LINE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.LINE_CLEAR_BUTTON).performClick()
        assertNull(latest)
    }

    @Test
    fun tinyDragIsDiscarded() {
        var calls = 0
        composeRule.setContent { Screen(onLine = { calls++ }) }
        composeRule.onNodeWithTag(TestTags.LINE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.LINE_EDITOR).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.5f + 2f, height * 0.5f), 100)
        }
        assertEquals(0, calls)
    }

    @Test
    fun finishLineEditorScreenshot() {
        composeRule.setContent { Screen(settings = CaptureSettings(finishLine = stored)) }
        composeRule.onNodeWithTag(TestTags.LINE_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_finish_line_editing.png")
    }

    @Test
    fun detectButtonRequestsDetectionAndEntersEditing() {
        var detects = 0
        composeRule.setContent {
            SetupScreen(
                settings = CaptureSettings(finishLine = stored),
                capabilities = caps,
                permission = PermissionUi.GRANTED,
                settingsOpen = false,
                onSettingsOpenChange = {},
                onSettingsChange = {},
                onFinishLineChange = {},
                onRequestPermissions = {},
                onOpenSystemSettings = {},
                onDetectLine = { detects++ },
                detectMessage = "Line suggested (84% sure). Drag the ends to adjust.",
                preview = { CameraPreviewPlaceholder() },
            )
        }
        composeRule.onNodeWithTag(TestTags.DETECT_BUTTON).performClick()
        assertEquals(1, detects)
        composeRule.onNodeWithTag(TestTags.LINE_EDITOR).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.DETECT_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.SETUP_SCREEN)
            .captureRoboImage("src/test/screenshots/setup_detect_line.png")
    }

    @Test
    fun detectMessagesCoverEveryOutcome() {
        assertTrue(detectMessageFor(false, null).startsWith("No camera frame"))
        assertTrue(detectMessageFor(true, null).startsWith("No white line"))
        val s = net.belllap.vision.finishline.LineSuggestion(stored, 0.84f)
        assertTrue(detectMessageFor(true, s).contains("84%"))
    }
}
