package net.belllap.vision.flag

/** An axis-aligned box in full-frame pixels. */
data class FlagBox(val x: Int, val y: Int, val width: Int, val height: Int)

/** A region of interest as fractions of the frame (0..1), so it survives a resolution change. */
data class FlagRoi(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0)
        require(left < right && top < bottom) { "empty ROI" }
    }
}

/**
 * Tunables of [ChequeredFlagDetector]. Times are PTS durations, never wall-clock.
 * The absence timeout that ends an acquisition is NOT here: it is `SessionConfig.flagAbsentTimeoutMs` (default
 * 15 s) in the state machine, which turns FlagLost into a stand-down.
 */
data class FlagConfig(
    /** Frames wider than this are box-averaged down by an integer factor first. */
    val analysisWidth: Int = 320,
    /** Only this part of the frame is searched. Null searches all of it. */
    val roi: FlagRoi? = null,
    val tile: Int = 8,
    /** Corner quadrant sizes tried, in analysis pixels. Covers cells of 4..12 px. */
    val cornerRadii: List<Int> = DEFAULT_RADII,
    /** Minimum luma gap between the two diagonals of an X-corner. */
    val cornerContrast: Int = 60,
    /** Minimum share of a tile's pixels that are clearly dark and clearly light (bimodal). */
    val bimodalShare: Double = 0.15,
    /** A pixel changed between frames if it moved by more than this. */
    val motionDelta: Int = 40,
    /** A tile is moving if this share of its pixels changed. */
    val motionShare: Double = 0.06,
    /** A tile counts as moving for this long after its last motion (a waved flag pauses between strokes). */
    val motionHoldNs: Long = 400_000_000L,
    /** The checkered, moving area must be at least this share of the analysed area (research: 0.3%). */
    val minAreaFraction: Double = 0.003,
    val minTiles: Int = 4,
    /** Tiles closer than this many tiles belong to one flag. */
    val joinTiles: Int = 2,
    /** Candidate must persist this long before FlagSeen (research: 0.5 s). */
    val onNs: Long = 500_000_000L,
    /** A gap in the candidate shorter than this does not restart the onset timer. */
    val onGapNs: Long = 250_000_000L,
    /** Visible stays on through gaps shorter than this; longer ends it (FlagLost). */
    val offNs: Long = 500_000_000L,
) {
    private companion object {
        val DEFAULT_RADII = listOf(2, 3, 4)
    }
}

/**
 * One frame's verdict. [box] is the flag (texture and motion), null if none. [textureBox] is the largest
 * chequered area regardless of motion, so a still banner shows up here but never in [box].
 */
data class FlagResult(val visible: Boolean, val box: FlagBox?, val textureBox: FlagBox?)
