package net.belllap.core.model

import java.io.File

/** The recorder could not start (camera not bound, storage error). The message is shown to the user. */
class RecordingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The files of an acquisition whose MP4 is finalized on disk. */
class SavedAcquisition(val id: String, val video: File, val sidecar: File)

/**
 * Where an acquisition goes (F17). The Android implementation records through CameraX; JVM tests use a fake. All
 * times are frame PTS in microseconds on the camera clock, never wall-clock time.
 */
interface AcquisitionSink {
    /** Free bytes in app-specific storage. */
    fun freeBytes(): Long

    /** Display names of the acquisitions already saved, for [AcquisitionNaming]. */
    fun existingNames(): List<String>

    /** PTS of the newest camera frame, or null while no frame has arrived. */
    fun latestFramePtsUs(): Long?

    /** Starts the MP4 `acq_<id>.mp4` for acquisition [id]. Throws [RecordingUnavailableException] when it cannot. */
    fun start(id: String)

    /** Stops the MP4 of [id] and writes its `acq_<id>.json` from [sidecarJson]. */
    fun stop(id: String, sidecarJson: String)

    /**
     * Set by the owner. Called on any thread once the MP4 of an acquisition is finalized, because stopping the
     * recorder returns before the file is complete. The post-process refinement (F18) starts from it. The default
     * sink never calls it.
     */
    var onSaved: ((SavedAcquisition) -> Unit)?
        get() = null
        set(
            @Suppress("UNUSED_PARAMETER") value
        ) = Unit
}
