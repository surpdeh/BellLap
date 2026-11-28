package net.belllap.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.Resolution
import net.belllap.vision.core.Frame
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BellLapCamera"

/** The camera could not be initialised or bound (no camera, init failure, in use). */
class CameraUnavailableException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Binds one CameraX [SessionConfig] of Preview, ImageAnalysis and VideoCapture for [settings] (F17). The setup screen
 * shows the preview for tripod alignment; the armed screens bind it with a discarded surface and record from it.
 */
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
        val recorder = Recorder.Builder().setQualitySelector(qualityFor(settings.resolution)).build()
        val video = VideoCapture.withOutput(recorder)
        val fps = Range(settings.fps.value, settings.fps.value)
        provider.unbindAll()
        latestPtsUs = null
        videoCapture = null
        val camera = provider.bindToLifecycle(
            owner,
            selector,
            SessionConfig(useCases = listOf(preview, analysis, video), frameRateRange = fps),
        )
        videoCapture = video
        Log.i(TAG, "bind: bound, cameraId=${camera.cameraInfo}")
        return probeCapabilities(camera.cameraInfo)
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** The bound recorder use case, or null while no camera is bound. Read by [CameraXAcquisitionSink]. */
    @Volatile
    internal var videoCapture: VideoCapture<Recorder>? = null
        private set

    /** PTS in microseconds (camera clock) of the newest analysis frame, or null before the first one. */
    @Volatile
    internal var latestPtsUs: Long? = null
        private set

    private fun qualityFor(resolution: Resolution) = QualitySelector.from(
        when (resolution) {
            Resolution.HD_720 -> Quality.HD
            Resolution.FHD_1080 -> Quality.FHD
            Resolution.UHD_2160 -> Quality.UHD
        },
        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
    )

    /**
     * Gives a [SurfaceRequest] a surface nobody looks at, so the bound session runs with no visible preview. The
     * dimmed standby screen stays black while analysis and recording carry on.
     */
    fun discardSurface(context: Context, request: SurfaceRequest) {
        val texture = SurfaceTexture(
            0,
        ).apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) }
        val surface = Surface(texture)
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
            surface.release()
            texture.release()
        }
    }

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
            latestPtsUs = it.imageInfo.timestamp / NANOS_PER_MICRO
            val request = wanted ?: return
            val plane = it.planes[0]
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining()).also { out -> buffer.get(out) }
            request.complete(Frame(it.imageInfo.timestamp, it.width, it.height, plane.rowStride, luma))
        }
    }

    private const val GRAB_TIMEOUT_MS = 3000L
    private const val NANOS_PER_MICRO = 1000L

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
