package net.belllap.ui

import net.belllap.vision.finishline.LineSuggestion

/** The one-line result shown next to the "Detect line" button. */
fun detectMessageFor(gotFrame: Boolean, found: LineSuggestion?): String = when {
    !gotFrame -> "No camera frame yet. Try again."
    found == null -> "No white line found. Draw it by hand."
    else -> "Line suggested (${(found.confidence * PERCENT).toInt()}% sure). Drag the ends to adjust."
}

private const val PERCENT = 100
