package net.belllap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import net.belllap.core.model.PreviewMapping
import net.belllap.core.statemachine.State
import net.belllap.debug.DebugEntry
import net.belllap.settings.SettingsRepository
import net.belllap.ui.CameraPreview
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
                var capabilities by remember { mutableStateOf<CameraCapabilities?>(null) }
                var mapping by remember { mutableStateOf(PreviewMapping()) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                var asked by rememberSaveable { mutableStateOf(false) }
                var granted by remember { mutableStateOf(allGranted()) }
                var rationale by remember { mutableStateOf(shouldShowRationale()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    asked = true
                    granted = allGranted()
                    rationale = shouldShowRationale()
                }
                if (state != State.Setup && granted) {
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
                            onFinishLineChange = { line -> scope.launch { repo.update { it.copy(finishLine = line) } } },
                            onSettingsChange = { new -> scope.launch { repo.update { new } } },
                            onRequestPermissions = { launcher.launch(REQUIRED_PERMISSIONS) },
                            onStandby = session::standby,
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
}
