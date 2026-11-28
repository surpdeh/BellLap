package net.belllap.core.model

/** One encoded access unit. [ptsUs] is the frame PTS on the camera clock, never wall-clock time. */
class EncodedSample(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean)

/** Receives the samples of a recording, in presentation order. Implemented over `MediaMuxer` on Android. */
interface SampleSink {
    fun write(sample: EncodedSample)
}

/**
 * The pre-roll of F30: keeps the last [windowUs] of encoded video in RAM, always starting on a key frame, so a
 * recording can begin with the seconds before the flag was seen. With a 1 s GOP the buffer holds between
 * [windowUs] and [windowUs] plus one GOP of video.
 *
 * Once [startRecording] is called, samples go straight to the sink. The hand-over happens under one lock, so the
 * splice neither drops nor repeats a sample. Output PTS are rebased so the first written sample is at 0, and a
 * sample that does not move PTS forward is dropped and counted in [droppedOutOfOrder].
 */
class PreRollBuffer(private val windowUs: Long) {
    init {
        require(windowUs > 0) { "windowUs must be positive" }
    }

    private val ring = ArrayDeque<EncodedSample>()
    private var sink: SampleSink? = null
    private var basePtsUs = 0L
    private var lastWrittenUs = Long.MIN_VALUE

    /** Samples dropped because their PTS did not advance. */
    var droppedOutOfOrder = 0
        private set

    val isRecording: Boolean @Synchronized get() = sink != null

    /** Number of samples currently held as pre-roll. */
    val size: Int @Synchronized get() = ring.size

    /** Called by the encoder thread for every encoded sample. */
    @Synchronized
    fun add(sample: EncodedSample) {
        val out = sink
        if (out != null) {
            write(out, sample)
            return
        }
        // Samples before the first key frame cannot be decoded on their own.
        if (ring.isEmpty() && !sample.keyFrame) return
        ring.addLast(sample)
        evict(sample.ptsUs - windowUs)
    }

    /**
     * Flushes the pre-roll to [sink] and routes every later sample there. Returns the PTS of the first sample
     * written, which is the offset removed from every output PTS, or null when no key frame was buffered yet (the
     * recording then starts at the next key frame).
     */
    @Synchronized
    fun startRecording(sink: SampleSink): Long? {
        check(this.sink == null) { "already recording" }
        this.sink = sink
        lastWrittenUs = Long.MIN_VALUE
        droppedOutOfOrder = 0
        basePtsUs = ring.firstOrNull()?.ptsUs ?: Long.MIN_VALUE
        val pending = ring.toList()
        ring.clear()
        pending.forEach { write(sink, it) }
        return pending.firstOrNull()?.ptsUs
    }

    /** Stops routing to the sink and starts buffering pre-roll again. */
    @Synchronized
    fun stopRecording() {
        sink = null
    }

    private fun write(out: SampleSink, sample: EncodedSample) {
        if (basePtsUs == Long.MIN_VALUE) { // nothing buffered at start: begin on the next key frame
            if (!sample.keyFrame) return
            basePtsUs = sample.ptsUs
        }
        if (sample.ptsUs <= lastWrittenUs) {
            droppedOutOfOrder++
            return
        }
        lastWrittenUs = sample.ptsUs
        out.write(EncodedSample(sample.data, sample.ptsUs - basePtsUs, sample.keyFrame))
    }

    /** Drops everything before the newest key frame at or before [cutoffUs], keeping the buffer key-frame aligned. */
    private fun evict(cutoffUs: Long) {
        val keep = ring.indexOfLast { it.keyFrame && it.ptsUs <= cutoffUs }
        repeat(keep.coerceAtLeast(0)) { ring.removeFirst() }
    }
}
