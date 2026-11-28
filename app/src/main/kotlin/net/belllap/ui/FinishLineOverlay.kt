package net.belllap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.belllap.core.model.FinishLine
import net.belllap.core.model.NormPoint
import net.belllap.core.model.PreviewMapping

private val LINE_COLOR = Color(0xFFFFEB3B)
private val HANDLE_RADIUS = 14.dp
private val GRAB_RADIUS = 36.dp

/** Which part of the line a drag moves. */
private enum class Grab { START, END }

/** The preview's content rectangle: [aspect] (w/h) fitted and centred in a box of [boxW] x [boxH]. */
internal fun fitRect(boxW: Float, boxH: Float, aspect: Float): Pair<Offset, Size> {
    val w = if (boxW / boxH > aspect) boxH * aspect else boxW
    val h = w / aspect
    return Offset((boxW - w) / 2f, (boxH - h) / 2f) to Size(w, h)
}

/**
 * Draws the stored [line] (sensor coordinates) over the preview and, while [editing], lets the user drag a
 * new line or drag either endpoint handle. Edits are committed through [onLineChange] when the drag ends.
 */
@Composable
fun FinishLineOverlay(
    line: FinishLine?,
    mapping: PreviewMapping,
    frameAspect: Float,
    editing: Boolean,
    onLineChange: (FinishLine?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The sensor frame is landscape; a 90/270 display rotation shows it portrait.
    val aspect = if (mapping.rotationDegrees % 180 == 0) frameAspect else 1f / frameAspect
    var draft by remember(line) { mutableStateOf(line) }
    val currentLine by rememberUpdatedState(line)
    val currentMapping by rememberUpdatedState(mapping)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }
    val handlePx = with(LocalDensity.current) { HANDLE_RADIUS.toPx() }
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(TestTags.LINE_OVERLAY)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val (origin, size) = fitRect(boxW, boxH, aspect)

        fun toScreen(p: NormPoint) = Offset(origin.x + p.x * size.width, origin.y + p.y * size.height)

        fun toDisplayNorm(o: Offset) =
            NormPoint.clampOf((o.x - origin.x) / size.width, (o.y - origin.y) / size.height)

        val gestureModifier = if (editing) {
            Modifier.testTag(TestTags.LINE_EDITOR).lineDragGestures(
                key = origin to size,
                grabPx = grabPx,
                draft = { draft },
                setDraft = { draft = it },
                previous = { currentLine },
                mapping = { currentMapping },
                toScreen = ::toScreen,
                toDisplayNorm = ::toDisplayNorm,
                onLineChange = onLineChange,
            )
        } else {
            Modifier
        }

        Canvas(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
            val shown = draft?.let { mapping.sensorToDisplay(it) } ?: return@Canvas
            drawFinishLine(toScreen(shown.start), toScreen(shown.end), editing, handlePx, strokePx)
        }
    }
}

@Suppress("LongParameterList")
private fun Modifier.lineDragGestures(
    key: Any,
    grabPx: Float,
    draft: () -> FinishLine?,
    setDraft: (FinishLine?) -> Unit,
    previous: () -> FinishLine?,
    mapping: () -> PreviewMapping,
    toScreen: (NormPoint) -> Offset,
    toDisplayNorm: (Offset) -> NormPoint,
    onLineChange: (FinishLine?) -> Unit,
): Modifier = pointerInput(key) {
    var grab: Grab? = null
    detectDragGestures(
        onDragStart = { down ->
            grab = draft()?.let { nearestHandle(mapping().sensorToDisplay(it), down, grabPx, toScreen) }
            if (grab == null) {
                val p = mapping().displayToSensor(toDisplayNorm(down))
                setDraft(FinishLine(p, p))
                grab = Grab.END
            }
        },
        onDrag = { change, _ ->
            change.consume()
            val cur = draft() ?: return@detectDragGestures
            val p = mapping().displayToSensor(toDisplayNorm(change.position))
            setDraft(if (grab == Grab.START) cur.copy(start = p) else cur.copy(end = p))
        },
        onDragEnd = { commit(draft(), previous(), onLineChange, setDraft) },
        onDragCancel = { setDraft(previous()) },
    )
}

private fun DrawScope.drawFinishLine(a: Offset, b: Offset, editing: Boolean, handlePx: Float, strokePx: Float) {
    drawLine(LINE_COLOR, a, b, strokeWidth = strokePx, cap = StrokeCap.Round)
    if (!editing) return
    for (p in listOf(a, b)) {
        drawCircle(Color.Black.copy(alpha = 0.4f), handlePx + strokePx, p)
        drawCircle(LINE_COLOR, handlePx, p, style = Stroke(width = strokePx))
    }
}

private fun nearestHandle(
    shown: FinishLine,
    touch: Offset,
    grabPx: Float,
    toScreen: (NormPoint) -> Offset,
): Grab? {
    val ds = (toScreen(shown.start) - touch).getDistance()
    val de = (toScreen(shown.end) - touch).getDistance()
    return when {
        ds > grabPx && de > grabPx -> null
        ds <= de -> Grab.START
        else -> Grab.END
    }
}

/** Commits a finished drag: unusable (tiny) lines are discarded and the previous line is restored. */
private fun commit(
    draft: FinishLine?,
    previous: FinishLine?,
    onChange: (FinishLine?) -> Unit,
    reset: (FinishLine?) -> Unit,
) {
    if (draft != null && draft.isUsable()) {
        onChange(draft)
    } else {
        reset(previous)
    }
}
