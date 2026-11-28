package net.belllap.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.belllap.core.model.Vec2

private const val TICK_INSET = 0.2f
private const val SELECTED_TICK_DP = 4
private const val TICK_DP = 2
private const val PLAYHEAD_DP = 7
private const val DISABLED_ALPHA = 0.4f

private val TrackColor = Color.White.copy(alpha = 0.25f)
private val TickColor = Color(0xFFFFB300)
private val SelectedColor = Color(0xFF40C4FF)

/**
 * The review player (F19): [video] is the picture (the ExoPlayer surface, or a placeholder in tests), zoomed and
 * panned from [state]; below it the scrubber with finisher ticks, the frame and finisher buttons and the mark edits.
 */
@Composable
fun ReviewPlayerScreen(
    state: ReviewState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    video: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .testTag(ReviewTags.PLAYER_SCREEN),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.sidecar.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.testTag(ReviewTags.CLOSE),
            ) { Text("Back", color = Color.White) }
        }
        ZoomableVideo(state, Modifier.weight(1f).fillMaxWidth(), video)
        StatusRow(state)
        Scrubber(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = state::previousFinisher,
                modifier = Modifier.weight(1f).testTag(ReviewTags.PREVIOUS_FINISHER),
            ) {
                Text("Prev")
            }
            Button(
                onClick = { state.step(-1) },
                modifier = Modifier.weight(1f).testTag(ReviewTags.STEP_BACK),
            ) { Text("-1") }
            Button(onClick = {
                state.step(1)
            }, modifier = Modifier.weight(1f).testTag(ReviewTags.STEP_FORWARD)) { Text("+1") }
            Button(
                onClick = state::nextFinisher,
                modifier = Modifier.weight(1f).testTag(ReviewTags.NEXT_FINISHER),
            ) { Text("Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = state::addMarkHere, modifier = Modifier.weight(1f).testTag(ReviewTags.ADD_MARK)) {
                Text("Add mark", color = Color.White)
            }
            val hasMark = state.selected != null
            MarkButton("Move here", hasMark, state::moveSelectedHere, ReviewTags.MOVE_MARK, Modifier.weight(1f))
            MarkButton("Delete mark", hasMark, state::deleteSelected, ReviewTags.DELETE_MARK, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MarkButton(label: String, enabled: Boolean, onClick: () -> Unit, tag: String, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA))
    }
}

/** Pinch zoom and pan. The gesture is read outside the graphicsLayer, so it sees untransformed coordinates. */
@Composable
private fun ZoomableVideo(state: ReviewState, modifier: Modifier, video: @Composable BoxScope.() -> Unit) {
    var viewport by remember { mutableStateOf(Vec2(1f, 1f)) }
    val zoom = state.zoomPan
    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color.DarkGray.copy(alpha = 0.3f))
            .onSizeChanged { viewport = Vec2(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(state) {
                detectTransformGestures { centroid, pan, zoomFactor, _ ->
                    state.applyGesture(Vec2(centroid.x, centroid.y), Vec2(pan.x, pan.y), zoomFactor, viewport)
                }
            }
            .testTag(ReviewTags.ZOOM_AREA),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.panX
                translationY = zoom.panY
            },
            content = video,
        )
    }
}

@Composable
private fun StatusRow(state: ReviewState) {
    val marks = state.sidecar.crossings.size
    val mark = state.selected?.let { "Mark ${it + 1} of $marks" } ?: "$marks marks"
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Frame ${state.frameIndex + 1} / ${state.lastIndex + 1}  $mark",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(ReviewTags.FRAME_LABEL),
        )
        Text(
            "Zoom %.1fx".format(state.zoomPan.scale),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(ReviewTags.ZOOM_LABEL),
        )
    }
}

/** Tap or drag to seek; a tick per finisher mark, the selected one highlighted. */
@Composable
private fun Scrubber(state: ReviewState) {
    var width by remember { mutableStateOf(1f) }

    fun fraction(x: Float) = (x / width).coerceIn(0f, 1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(state) { detectTapGestures { state.scrubTo(fraction(it.x)) } }
            .pointerInput(state) {
                detectHorizontalDragGestures {
                        change,
                        _,
                    ->
                    state.scrubTo(fraction(change.position.x))
                }
            }
            .testTag(ReviewTags.SCRUBBER),
    ) {
        val mid = size.height / 2

        fun x(frame: Int) = size.width * frame / state.lastIndex
        drawLine(TrackColor, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 4.dp.toPx())
        state.sidecar.crossings.indices.forEach { i ->
            val selected = i == state.selected
            drawLine(
                if (selected) SelectedColor else TickColor,
                Offset(x(state.markFrame(i)), if (selected) 0f else size.height * TICK_INSET),
                Offset(x(state.markFrame(i)), if (selected) size.height else size.height * (1 - TICK_INSET)),
                strokeWidth = (if (selected) SELECTED_TICK_DP else TICK_DP).dp.toPx(),
            )
        }
        drawCircle(Color.White, radius = PLAYHEAD_DP.dp.toPx(), center = Offset(x(state.frameIndex), mid))
    }
}
