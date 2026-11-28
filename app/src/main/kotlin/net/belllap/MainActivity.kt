package net.belllap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.belllap.capture.PreviewSession
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.PreviewMapping
import net.belllap.core.service.SessionForegroundService
import net.belllap.core.service.dimmedStandby
import net.belllap.core.statemachine.State
import net.belllap.debug.DebugEntry
import net.belllap.settings.SettingsRepository
import net.belllap.ui.CameraPreview
import net.belllap.ui.OEM_TIPS_URL
import net.belllap.ui.OnboardingScreen
import net.belllap.ui.OnboardingStatus
import net.belllap.ui.REQUIRED_PERMISSIONS
import net.belllap.ui.SetupScreen
import net.belllap.ui.StandbyScreen
import net.belllap.ui.detectMessageFor
import net.belllap.ui.permissionUi
import net.belllap.vision.finishline.WhiteLineDetector

private val android.content.Context.settingsStore by preferencesDataStore("capture_settings")

class MainActivity : ComponentActivity() {
    private fun allGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun shouldShowRationale() =
        REQUIRED_PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Dimmed standby: screen kept on and brightness about 0.01; both restored when the session leaves STANDBY. */
    private fun applyDimming(dimmed: Boolean) {
        val attrs = window.attributes
        attrs.screenBrightness = if (dimmed) DIMMED_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        if (dimmed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Composable
    private fun Onboarding(granted: Boolean, requestCameraMic: () -> Unit, onDone: () -> Unit) {
        var notifications by remember { mutableStateOf(notificationsGranted()) }
        var battery by remember { mutableStateOf(batteryExempt()) }
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifications = notificationsGranted()
        }
        val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            battery = batteryExempt()
        }
        OnboardingScreen(
            status = OnboardingStatus(notifications, granted, battery),
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onRequestCameraMic = requestCameraMic,
            onRequestBatteryExemption = {
                val uri = Uri.fromParts("package", packageName, null)
                batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
            },
            onOpenOemTips = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OEM_TIPS_URL))) },
            onContinue = onDone,
        )
    }

    /** Onboarding shows once per launch, from SETUP, until the user continues or everything is already granted. */
    private fun showOnboarding(state: State, onboarded: Boolean, granted: Boolean): Boolean {
        val ready = granted && notificationsGranted() && batteryExempt()
        return state == State.Setup && !onboarded && !ready
    }

    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(settingsStore)
        val session = (application as BellLapApp).session
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val settings by repo.settings.collectAsState(initial = CaptureSettings())
                val state by session.state.collectAsState()
                val bell by session.bell.collectAsState()
                LaunchedEffect(settings) { session.updateSettings(settings) }
                LaunchedEffect(dimmedStandby(state)) { applyDimming(dimmedStandby(state)) }
                var onboarded by rememberSaveable { mutableStateOf(false) }
                var capabilities by remember { mutableStateOf<CameraCapabilities?>(null) }
                var mapping by remember { mutableStateOf(PreviewMapping()) }
                var detectMessage by remember { mutableStateOf<String?>(null) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var asked by rememberSaveable { mutableStateOf(false) }
                var granted by remember { mutableStateOf(allGranted()) }
                var rationale by remember { mutableStateOf(shouldShowRationale()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    asked = true
                    granted = allGranted()
                    rationale = shouldShowRationale()
                }
                if (showOnboarding(state, onboarded, granted)) {
                    Onboarding(granted, { launcher.launch(REQUIRED_PERMISSIONS) }, { onboarded = true })
                } else if (state != State.Setup && granted) {
                    StandbyScreen(state, bell, onBellNow = session::bellNow, onStop = session::stop)
                } else {
                    Box {
                        SetupScreen(
                            settings = settings,
                            capabilities = capabilities,
                            permission = permissionUi(granted, asked, rationale),
                            settingsOpen = settingsOpen,
                            onSettingsOpenChange = { settingsOpen = it },
                            mapping = mapping,
                            onFinishLineChange = { line ->
                                scope.launch { repo.update { it.copy(finishLine = line) } }
                            },
                            onSettingsChange = { new -> scope.launch { repo.update { new } } },
                            onRequestPermissions = { launcher.launch(REQUIRED_PERMISSIONS) },
                            onStandby = { SessionForegroundService.startStandby(this@MainActivity) },
                            detectMessage = detectMessage,
                            onDetectLine = { scope.launch { detectMessage = detectAndStoreLine(repo) } },
                            onOpenSystemSettings = {
                                val uri = Uri.fromParts("package", packageName, null)
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                            },
                            preview = {
                                CameraPreview(settings, onMapping = { mapping = it }) { caps ->
                                    capabilities = caps
                                    val fixed = settings.coerceTo(caps)
                                    if (fixed != settings) scope.launch { repo.update { fixed } }
                                }
                            },
                        )
                        DebugEntry()
                    }
                }
            }
        }
    }

    /** Grabs a frame, runs the F12 detector and stores a found line; returns the message to show. */
    private suspend fun detectAndStoreLine(repo: SettingsRepository): String {
        val frame = PreviewSession.grabFrame()
        val found = frame?.let { withContext(Dispatchers.Default) { WhiteLineDetector.detect(it) } }
        found?.let { s -> repo.update { it.copy(finishLine = s.line) } }
        return detectMessageFor(frame != null, found)
    }

    private companion object {
        const val DIMMED_BRIGHTNESS = 0.01f
    }
}
