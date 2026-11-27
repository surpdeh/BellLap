package net.belllap.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.belllap.BellLapApp

/** Test handles for the device capability screen. */
object DeviceTags {
    const val OPEN = "device_open"
    const val SCREEN = "device_screen"
    const val JSON = "device_json"
    const val CLOSE = "device_close"
}

/** Debug-only: shows the device capability JSON that F25 saved and bug reports include. */
@Composable
fun DeviceScreen(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as BellLapApp
    val caps by app.deviceCapabilities.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .testTag(DeviceTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            caps?.toJson() ?: "Probing the device...",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag(DeviceTags.JSON),
        )
        OutlinedButton(onClose, Modifier.testTag(DeviceTags.CLOSE)) { Text("Close") }
    }
}
