package net.belllap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
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
import net.belllap.settings.SettingsRepository
import net.belllap.ui.CameraPreview
import net.belllap.ui.REQUIRED_PERMISSIONS
import net.belllap.ui.SetupScreen
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
                SetupScreen(
                    settings = settings,
                    capabilities = capabilities,
                    permission = permissionUi(granted, asked, rationale),
                    settingsOpen = settingsOpen,
                    onSettingsOpenChange = { settingsOpen = it },
                    onSettingsChange = { new -> scope.launch { repo.update { new } } },
                    onRequestPermissions = { launcher.launch(REQUIRED_PERMISSIONS) },
                    onOpenSystemSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                        )
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
}
