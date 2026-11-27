package net.belllap.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.belllap.capture.PreviewSession
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CaptureSettings

/** Full-screen CameraX preview. Rebinds when camera, resolution or fps change. */
@Composable
fun CameraPreview(settings: CaptureSettings, onCapabilities: (CameraCapabilities) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(settings.facing, settings.resolution, settings.fps) {
        onCapabilities(PreviewSession.bind(context, owner, settings) { request = it })
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}
