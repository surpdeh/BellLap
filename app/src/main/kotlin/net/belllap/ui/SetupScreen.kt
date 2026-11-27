package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.FinishLine
import net.belllap.core.model.Fps
import net.belllap.core.model.PreviewMapping
import net.belllap.core.model.Resolution

@Composable
fun SetupScreen(
    settings: CaptureSettings,
    capabilities: CameraCapabilities?,
    permission: PermissionUi,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
    onSettingsChange: (CaptureSettings) -> Unit,
    onFinishLineChange: (FinishLine?) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStandby: () -> Unit = {},
    onDetectLine: () -> Unit = {},
    detectMessage: String? = null,
    mode: String = "SETUP",
    mapping: PreviewMapping = PreviewMapping(),
    preview: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TestTags.SETUP_SCREEN),
    ) {
        if (permission == PermissionUi.GRANTED) {
            preview()
        } else {
            PermissionRationale(permission, onRequestPermissions, onOpenSystemSettings)
        }
        var lineEditing by rememberSaveable { mutableStateOf(false) }
        if (permission == PermissionUi.GRANTED) {
            FinishLineOverlay(
                line = settings.finishLine,
                mapping = mapping,
                frameAspect = settings.resolution.width.toFloat() / settings.resolution.height,
                editing = lineEditing,
                onLineChange = onFinishLineChange,
            )
        }
        StatusOverlay(
            mode = mode,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        )
        if (permission == PermissionUi.GRANTED) {
            Button(
                onClick = { onSettingsOpenChange(!settingsOpen) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .testTag(TestTags.SETTINGS_BUTTON),
            ) { Text(if (settingsOpen) "Close" else "Settings") }
            if (!settingsOpen) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 16.dp, top = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { lineEditing = !lineEditing },
                        modifier = Modifier.testTag(TestTags.LINE_BUTTON),
                    ) { Text(if (lineEditing) "Done" else "Finish line") }
                    Button(
                        onClick = {
                            lineEditing = true
                            onDetectLine()
                        },
                        modifier = Modifier.testTag(TestTags.DETECT_BUTTON),
                    ) { Text("Detect line") }
                    detectMessage?.let {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag(TestTags.DETECT_MESSAGE),
                        )
                    }
                    if (lineEditing && settings.finishLine != null) {
                        Button(
                            onClick = { onFinishLineChange(null) },
                            modifier = Modifier.testTag(TestTags.LINE_CLEAR_BUTTON),
                        ) { Text("Clear") }
                    }
                }
            }
        }
        if (permission == PermissionUi.GRANTED && !settingsOpen) {
            Button(
                onClick = onStandby,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp)
                    .testTag(TestTags.STANDBY_BUTTON),
            ) { Text("Standby") }
        }
        if (settingsOpen && permission == PermissionUi.GRANTED) {
            SettingsOverlay(
                settings = settings,
                capabilities = capabilities,
                onChange = onSettingsChange,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 72.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
fun CameraPreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.CAMERA_PREVIEW_PLACEHOLDER),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Camera preview",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun PermissionRationale(
    permission: PermissionUi,
    onRequest: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
            .testTag(TestTags.PERMISSION_RATIONALE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera and microphone access", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "BellLap uses the camera to see the finish line and the chequered flag, and the " +
                "microphone to hear the bell. Nothing leaves this phone.",
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(vertical = 16.dp).widthIn(max = 480.dp),
        )
        if (permission == PermissionUi.BLOCKED) {
            Text(
                "Access was denied. Turn on Camera and Microphone in system settings.",
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Button(onClick = onOpenSystemSettings, modifier = Modifier.testTag(TestTags.OPEN_SETTINGS_BUTTON)) {
                Text("Open system settings")
            }
        } else {
            Button(onClick = onRequest, modifier = Modifier.testTag(TestTags.GRANT_BUTTON)) {
                Text("Grant access")
            }
        }
    }
}

@Composable
internal fun StatusOverlay(mode: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TestTags.STATUS_OVERLAY),
    ) {
        Text(
            text = mode,
            color = Color.Black,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(TestTags.STATUS_MODE),
        )
    }
}

@Composable
fun SettingsOverlay(
    settings: CaptureSettings,
    capabilities: CameraCapabilities?,
    onChange: (CaptureSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolutions = capabilities?.supportedResolutions()?.takeIf { it.isNotEmpty() }
    val timeoutRange = CaptureSettings.FLAG_TIMEOUT_RANGE_S
    val fpsOptions = capabilities?.supportedFps()?.takeIf { it.isNotEmpty() }
    Surface(
        modifier = modifier.widthIn(max = 360.dp).testTag(TestTags.SETTINGS_OVERLAY),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.95f),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label("Camera")
            Chips(
                options = CameraFacing.entries,
                selected = settings.facing,
                label = { if (it == CameraFacing.BACK) "Back" else "Front" },
                enabled = { true },
            ) { onChange(settings.copy(facing = it)) }
            Label("Resolution")
            Chips(
                options = Resolution.entries,
                selected = settings.resolution,
                label = { "${it.height}p" },
                enabled = { resolutions == null || it in resolutions },
            ) { onChange(settings.copy(resolution = it)) }
            Label("Frame rate")
            Chips(Fps.entries, settings.fps, { "${it.value} fps" }, { fpsOptions == null || it in fpsOptions }) {
                onChange(settings.copy(fps = it))
            }
            Label("Automation")
            ToggleRow("Bell detection", settings.bellAutomation, TestTags.TOGGLE_BELL) {
                onChange(settings.copy(bellAutomation = it))
            }
            Label("Bell sensitivity")
            Chips(
                options = BellSensitivity.entries,
                selected = settings.bellSensitivity,
                label = ::sensitivityLabel,
                enabled = { settings.bellAutomation },
            ) { onChange(settings.copy(bellSensitivity = it)) }
            ToggleRow("Flag detection", settings.flagAutomation, TestTags.TOGGLE_FLAG) {
                onChange(settings.copy(flagAutomation = it))
            }
            ToggleRow("Crossing beeps", settings.crossingBeeps, TestTags.TOGGLE_BEEPS) {
                onChange(settings.copy(crossingBeeps = it))
            }
            Label("Flag timeout: ${settings.flagTimeoutSeconds} s")
            Slider(
                value = settings.flagTimeoutSeconds.toFloat(),
                onValueChange = { onChange(settings.copy(flagTimeoutSeconds = it.toInt())) },
                valueRange = timeoutRange.first.toFloat()..timeoutRange.last.toFloat(),
                modifier = Modifier.testTag(TestTags.FLAG_TIMEOUT_SLIDER),
            )
            val volume = settings.alertVolumePercent
            ToggleRow("Override alert volume", volume != null, TestTags.TOGGLE_VOLUME) {
                onChange(settings.copy(alertVolumePercent = if (it) DEFAULT_OVERRIDE_VOLUME else null))
            }
            if (volume != null) {
                Label("Alert volume: $volume%")
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { onChange(settings.copy(alertVolumePercent = it.toInt())) },
                    valueRange = 0f..100f,
                    modifier = Modifier.testTag(TestTags.VOLUME_SLIDER),
                )
            }
        }
    }
}

private const val DEFAULT_OVERRIDE_VOLUME = 80

fun sensitivityLabel(s: BellSensitivity): String = when (s) {
    BellSensitivity.LOW -> "Low"
    BellSensitivity.MEDIUM -> "Medium"
    BellSensitivity.HIGH -> "High"
}

@Composable
private fun Label(text: String) =
    Text(text, color = Color.Black, style = MaterialTheme.typography.labelLarge)

@Composable
private fun <T> Chips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled(option),
                label = { Text(label(option)) },
                modifier = Modifier.testTag("chip_${label(option)}"),
            )
        }
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.Black)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
