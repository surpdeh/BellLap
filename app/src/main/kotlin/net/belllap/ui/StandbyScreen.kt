package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.belllap.core.statemachine.State
import net.belllap.session.BellStatus

private val BellAmber = Color(0xFFFFB300)
private val NoticeRed = Color(0xFFFF8A80)

/**
 * STANDBY and the modes after it until the camera screens exist (F13, F16, F17). Black, because the default
 * standby is dimmed and kept on. The big "Bell now" button arms by hand and is always there in STANDBY, and
 * "Record now" starts an acquisition by hand in ARMED (F17). [notice] shows why a recording did not start.
 */
@Composable
fun StandbyScreen(
    state: State,
    bell: BellStatus,
    onBellNow: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onRecordNow: () -> Unit = {},
    notice: String? = null,
) {
    val inner = if (state is State.Review) state.underlying else state
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TestTags.STANDBY_SCREEN),
    ) {
        StatusOverlay(
            mode = modeName(state),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (inner == State.Standby) {
                Button(
                    onClick = onBellNow,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = BellAmber, contentColor = Color.Black),
                    modifier = Modifier.size(BELL_BUTTON_DP.dp).testTag(TestTags.BELL_NOW_BUTTON),
                ) {
                    Text("Bell now", fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            if (inner == State.Armed) {
                OutlinedButton(onClick = onRecordNow, modifier = Modifier.testTag(TestTags.RECORD_NOW_BUTTON)) {
                    Text("Record now", color = Color.White, fontSize = 24.sp)
                }
            }
            notice?.let {
                Text(
                    text = it,
                    color = NoticeRed,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TestTags.RECORD_NOTICE),
                )
            }
            Text(
                text = statusLine(inner, bell),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.BELL_STATUS),
            )
        }
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
                .testTag(TestTags.STOP_BUTTON),
        ) { Text("Stop", color = Color.White) }
    }
}

private const val BELL_BUTTON_DP = 220

fun modeName(state: State): String = when (state) {
    State.Setup -> "SETUP"
    State.Standby -> "STANDBY"
    State.Arming -> "ARMING"
    State.Armed -> "ARMED"
    is State.Acquisition -> "ACQUISITION"
    is State.Review -> "REVIEW"
}

internal fun statusLine(state: State, bell: BellStatus): String = when {
    state == State.Arming -> "Bell lap! Arming the camera."
    state == State.Armed -> "Armed. Waiting for the chequered flag."
    state is State.Acquisition -> "Recording the finish."
    state != State.Standby -> ""
    bell.error != null -> "Bell detection stopped: ${bell.error}. Use Bell now."
    !bell.listening -> "Bell detection is off. Use Bell now."
    else -> buildString {
        append("Listening for the bell (${sensitivityLabel(bell.sensitivity)} sensitivity")
        if (bell.classifier == "stage0-only") append(", no model: spectral gate only")
        append(")")
    }
}
