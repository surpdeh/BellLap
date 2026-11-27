package net.belllap.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.belllap.capture.CameraUnavailableException
import net.belllap.capture.PreviewSession
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.PreviewMapping
import timber.log.Timber

/** Full-screen CameraX preview. Rebinds when camera, resolution or fps change. */
@Composable
fun CameraPreview(
    settings: CaptureSettings,
    onMapping: (PreviewMapping) -> Unit,
    onCapabilities: (CameraCapabilities) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.facing, settings.resolution, settings.fps) {
        error = try {
            onCapabilities(PreviewSession.bind(context, owner, settings) { request = it })
            null
        } catch (e: CameraUnavailableException) {
            Timber.w(e, "Camera preview unavailable")
            e.message ?: "Camera unavailable"
        }
    }
    val mirrored = settings.facing == CameraFacing.FRONT
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    request?.let { req ->
        DisposableEffect(req, mirrored) {
            req.setTransformationInfoListener(executor) { onMapping(PreviewMapping(it.rotationDegrees, mirrored)) }
            onDispose { req.clearTransformationInfoListener() }
        }
        // Fit (not Crop) so the whole sensor frame is visible and finish-line coordinates map exactly.
        CameraXViewfinder(surfaceRequest = req, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
    error?.let {
        Box(Modifier.fillMaxSize().testTag(TestTags.CAMERA_ERROR), contentAlignment = Alignment.Center) {
            Text(
                text = "Camera unavailable\n$it",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}
