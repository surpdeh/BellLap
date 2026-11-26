package net.belllap.vision.core

import kotlinx.coroutines.flow.Flow

/** One 8-bit luma frame. [ptsNs] is the presentation time, the only time base detectors may use. */
class Frame(val ptsNs: Long, val width: Int, val height: Int, val stride: Int, val luma: ByteArray) {
    init {
        require(
            stride >= width && luma.size >= stride * height,
        ) { "luma buffer too small for ${width}x$height stride $stride" }
    }

    fun lumaAt(x: Int, y: Int): Int = luma[y * stride + x].toInt() and BYTE_MASK

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/** Where frames come from: CameraX, a decoded video, or an image sequence. Detectors only see this interface. */
interface FrameSource {
    /** Cold flow. Each collection starts from the first frame. */
    fun frames(): Flow<Frame>
}
