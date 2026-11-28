package net.belllap.core.model

/** The recorder could not start (camera not bound, storage error). The message is shown to the user. */
class RecordingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    /** Starts the MP4 for acquisition [id]. Throws [RecordingUnavailableException] when it cannot. */
    fun start(id: String)

    /** Stops the MP4 of [id] and writes its `acq_<id>.json` from [sidecarJson]. */
    fun stop(id: String, sidecarJson: String)
}
