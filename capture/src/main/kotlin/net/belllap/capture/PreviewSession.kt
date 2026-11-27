package net.belllap.capture

import android.content.Context
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.vision.core.Frame
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BellLapCamera"

/** The camera could not be initialised or bound (no camera, init failure, in use). */
class CameraUnavailableException(message: String, cause: Throwable) : Exception(message, cause)

/** Binds a CameraX Preview for [settings]; used by the setup screen for tripod alignment. */
object PreviewSession {
    /**
     * Binds the preview and returns the probed capabilities of the chosen camera.
     * Throws [CameraUnavailableException] when the camera cannot be initialised or bound.
     */
    suspend fun bind(
        context: Context,
        owner: LifecycleOwner,
        settings: CaptureSettings,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
    ): CameraCapabilities = try {
        bindOrThrow(context, owner, settings, onSurfaceRequest)
    } catch (e: ExecutionException) {
        throw CameraUnavailableException("Camera failed to initialise: ${e.cause?.message ?: e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw CameraUnavailableException("No matching camera: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw CameraUnavailableException("Camera could not be bound: ${e.message}", e)
    }

    private suspend fun bindOrThrow(
        context: Context,
        owner: LifecycleOwner,
        settings: CaptureSettings,
        onSurfaceRequest: (SurfaceRequest) -> Unit,
    ): CameraCapabilities {
        Log.i(TAG, "bind: awaiting provider for ${settings.facing} ${settings.resolution} ${settings.fps}")
        val provider = awaitProvider(context)
        Log.i(TAG, "bind: provider ready, cameras=${provider.availableCameraInfos.size}")
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
            .also {
                it.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    Log.i(TAG, "surface requested: ${request.resolution}")
                    onSurfaceRequest(request)
                }
            }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::onAnalysisFrame) }
        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        Log.i(TAG, "bind: bound, cameraId=${camera.cameraInfo}")
        return probeCapabilities(camera.cameraInfo)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var wanted: CompletableDeferred<Frame>? = null

    /**
     * The next camera frame as unrotated sensor luma, or null if none arrives within [timeoutMs] (camera not
     * bound). Frames are copied only while a caller is waiting, so the idle preview pays nothing for this.
     */
    suspend fun grabFrame(timeoutMs: Long = GRAB_TIMEOUT_MS): Frame? {
        val request = CompletableDeferred<Frame>()
        wanted = request
        return try {
            withTimeoutOrNull(timeoutMs) { request.await() }
        } finally {
            if (wanted === request) wanted = null
        }
    }

    private fun onAnalysisFrame(image: ImageProxy) {
        image.use {
            val request = wanted ?: return
            val plane = it.planes[0]
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining()).also { out -> buffer.get(out) }
            request.complete(Frame(it.imageInfo.timestamp, it.width, it.height, plane.rowStride, luma))
        }
    }

    private const val GRAB_TIMEOUT_MS = 3000L

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    // Guava swallows listener exceptions, so resume the coroutine explicitly on failure.
                    try {
                        cont.resume(future.get())
                    } catch (e: ExecutionException) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}
