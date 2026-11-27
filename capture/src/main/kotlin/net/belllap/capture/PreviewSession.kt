package net.belllap.capture

import android.content.Context
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import kotlin.coroutines.resume

/** Binds a CameraX Preview for [settings]; used by the setup screen for tripod alignment. */
object PreviewSession {
    /** Binds the preview and returns the probed capabilities of the chosen camera. */
    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        settings: CaptureSettings,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
    ): CameraCapabilities {
        val provider = awaitProvider(context)
        val selector = if (settings.facing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(settings.resolution.width, settings.resolution.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetFrameRate(Range(settings.fps.value, settings.fps.value))
            .build()
            .also { it.setSurfaceProvider(ContextCompat.getMainExecutor(context), onSurfaceRequest) }
        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, selector, preview)
        return probeCapabilities(camera.cameraInfo)
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
}
