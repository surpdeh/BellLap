package net.belllap.capture

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import androidx.camera.camera2.interop.cameraCharacteristics
import androidx.camera.core.CameraInfo
import net.belllap.core.model.CameraCapabilities
import net.belllap.core.model.OutputSize

/** Reads fps ranges and output sizes of a bound camera into the pure [CameraCapabilities]. */
fun probeCapabilities(info: CameraInfo): CameraCapabilities {
    val c2 = info.cameraCharacteristics
    val ranges: Array<Range<Int>> =
        c2.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
    val map = c2.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
    return CameraCapabilities(
        outputSizes = sizes.map { OutputSize(it.width, it.height) },
        fpsRanges = ranges.map { it.lower..it.upper },
    )
}
