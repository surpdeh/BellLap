package net.belllap.audio.android

import android.content.Context
import net.belllap.audio.bell.AcceptAllClassifier
import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.bell.YamnetClassMap
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Stage 1 of the bell cascade on the device: YAMNet through LiteRT, on one window of 15 600 samples.
 * The model and its label list are build-time assets (`:audio:android:fetchYamnet`). Not thread safe:
 * one detector owns one instance and calls it from its audio coroutine.
 */
class YamnetBellClassifier(model: MappedByteBuffer, val classMap: YamnetClassMap) : BellClassifier, AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
    private val output = Array(1) { FloatArray(classMap.names.size) }

    init {
        val outShape = interpreter.getOutputTensor(0).shape()
        check(outShape.last() == classMap.names.size) {
            "model has ${outShape.last()} classes, labels ${classMap.names.size}"
        }
    }

    override val windowSamples: Int = YamnetClassMap.WINDOW_SAMPLES
    override val name: String = "yamnet"

    /** All 521 class scores for one window, for diagnostics and the instrumented test. */
    fun scores(window: FloatArray): FloatArray {
        require(window.size == windowSamples) { "YAMNet takes $windowSamples samples, got ${window.size}" }
        interpreter.run(window, output)
        return output[0].copyOf()
    }

    override fun bellScore(window: FloatArray): Float = classMap.bellScore(scores(window))

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        const val LABELS_ASSET = "yamnet_labels.txt"

        fun load(context: Context): YamnetBellClassifier {
            val labels = context.assets.open(LABELS_ASSET).bufferedReader().use { YamnetClassMap.parse(it.readText()) }
            val model = context.assets.openFd(MODEL_ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            return YamnetBellClassifier(model, labels)
        }

        /** YAMNet, or stage 0 + persistence alone if the model cannot load (logged, never fatal). */
        fun loadOrFallback(context: Context): BellClassifier = runCatching { load(context) }
            .onFailure { Timber.e(it, "YAMNet did not load; bell detection runs on the spectral gate alone") }
            .getOrDefault(AcceptAllClassifier)
    }
}
