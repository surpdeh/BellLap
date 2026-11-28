package net.belllap.capture

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import net.belllap.core.model.AcquisitionSink
import net.belllap.core.model.RecordingUnavailableException
import net.belllap.core.model.SavedAcquisition
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarFormatException
import java.io.File
import java.io.IOException

private const val TAG = "BellLapRecorder"

/**
 * Records each acquisition to `acq_<id>.mp4` plus `acq_<id>.json` in app-specific storage ([dir]), through the
 * CameraX [androidx.camera.video.Recorder] bound by [PreviewSession]. Video only: the microphone belongs to the bell
 * listener, and a finish needs no sound.
 */
class CameraXAcquisitionSink(private val context: Context, private val dir: File) : AcquisitionSink {
    private var recording: Recording? = null

    override var onSaved: ((SavedAcquisition) -> Unit)? = null

    /** Space the system would let this app allocate, counting clearable caches; usable space if that fails. */
    override fun freeBytes(): Long {
        dir.mkdirs()
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (_: IOException) {
            dir.usableSpace
        }
    }

    override fun existingNames(): List<String> =
        dir.listFiles { f -> f.name.startsWith("acq_") && f.extension == "json" }.orEmpty().mapNotNull { f ->
            try {
                Sidecar.decode(f.readText()).name
            } catch (_: SidecarFormatException) {
                null
            } catch (_: IOException) {
                null
            }
        }

    override fun latestFramePtsUs(): Long? = PreviewSession.latestPtsUs

    @Suppress("ThrowsCount")
    override fun start(id: String) {
        val video = PreviewSession.videoCapture ?: throw RecordingUnavailableException("The camera is not ready.")
        if (recording != null) throw RecordingUnavailableException("A recording is already running.")
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw RecordingUnavailableException(
                "Cannot create the recordings folder.",
            )
        }
        val mp4 = File(dir, "acq_$id.mp4")
        val options = FileOutputOptions.Builder(mp4).build()
        recording = video.output.prepareRecording(context, options)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) {
                        Log.w(TAG, "recording $id finalized with error ${event.error}", event.cause)
                    } else {
                        onSaved?.invoke(SavedAcquisition(id, mp4, File(dir, "acq_$id.json")))
                    }
                }
            }
    }

    override fun stop(id: String, sidecarJson: String) {
        recording?.stop()
        recording = null
        try {
            File(dir, "acq_$id.json").writeText(sidecarJson)
        } catch (e: IOException) {
            throw RecordingUnavailableException("The sidecar could not be saved: ${e.message}", e)
        }
    }
}
