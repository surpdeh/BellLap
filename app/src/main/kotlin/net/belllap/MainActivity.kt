package net.belllap

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.Manifest
import android.view.WindowManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CaptureSettings
import net.belllap.core.service.SessionForegroundService
import net.belllap.core.service.dimmedStandby
import net.belllap.settings.SettingsRepository
import net.belllap.ui.CameraPreview
import net.belllap.ui.OEM_TIPS_URL
import net.belllap.ui.OnboardingScreen
import net.belllap.ui.OnboardingStatus
import net.belllap.ui.REQUIRED_PERMISSIONS
import net.belllap.ui.SetupScreen
import net.belllap.ui.StandbyScreen
import net.belllap.ui.permissionUi

private val android.content.Context.settingsStore by preferencesDataStore("capture_settings")

class MainActivity : ComponentActivity() {
    private fun allGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun shouldShowRationale() =
        REQUIRED_PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

    private fun notificationsGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** Black UI, screen kept on, brightness about 0.01 while in standby; restored otherwise. */
    private fun applyDimming(dimmed: Boolean) {
        val attrs = window.attributes
        attrs.screenBrightness =
            if (dimmed) DIMMED_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        if (dimmed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(settingsStore)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val settings by repo.settings.collectAsState(initial = CaptureSettings())
                var capabilities by remember { mutableStateOf<CameraCapabilities?>(null) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var asked by rememberSaveable { mutableStateOf(false) }
                var granted by remember { mutableStateOf(allGranted()) }
                var rationale by remember { mutableStateOf(shouldShowRationale()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    asked = true
                    granted = allGranted()
                    rationale = shouldShowRationale()
                }
                var onboarded by rememberSaveable { mutableStateOf(false) }
                var notifications by remember { mutableStateOf(notificationsGranted()) }
                var battery by remember { mutableStateOf(batteryExempt()) }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    notifications = notificationsGranted()
                }
                val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    battery = batteryExempt()
                }
                val sessionState by SessionForegroundService.state.collectAsState()
                val dimmed = dimmedStandby(sessionState)
                LaunchedEffect(dimmed) { applyDimming(dimmed) }
                if (dimmed) {
                    StandbyScreen(
                        onBellNow = { SessionForegroundService.bellNow(this@MainActivity) },
                        onStop = { SessionForegroundService.stop(this@MainActivity) },
                    )
                    return@MaterialTheme
                }
                if (!onboarded && !(granted && notifications && battery)) {
                    OnboardingScreen(
                        status = OnboardingStatus(notifications, granted, battery),
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onRequestCameraMic = { launcher.launch(REQUIRED_PERMISSIONS) },
                        onRequestBatteryExemption = {
                            val uri = Uri.fromParts("package", packageName, null)
                            batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
                        },
                        onOpenOemTips = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OEM_TIPS_URL))) },
                        onContinue = { onboarded = true },
                    )
                    return@MaterialTheme
                }
                SetupScreen(
                    settings = settings,
                    capabilities = capabilities,
                    permission = permissionUi(granted, asked, rationale),
                    settingsOpen = settingsOpen,
                    onSettingsOpenChange = { settingsOpen = it },
                    onSettingsChange = { new -> scope.launch { repo.update { new } } },
                    mode = sessionState::class.simpleName.orEmpty().uppercase(),
                    onStartStandby = { SessionForegroundService.startStandby(this@MainActivity) },
                    onRequestPermissions = { launcher.launch(REQUIRED_PERMISSIONS) },
                    onOpenSystemSettings = {
                        val uri = Uri.fromParts("package", packageName, null)
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    },
                    preview = {
                        CameraPreview(settings) { caps ->
                            capabilities = caps
                            val fixed = settings.coerceTo(caps)
                            if (fixed != settings) scope.launch { repo.update { fixed } }
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val DIMMED_BRIGHTNESS = 0.01f
    }
}
