package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Dimmed standby: an all-black screen with the bare minimum of controls. Brightness is set by the activity. */
@Composable
fun StandbyScreen(onBellNow: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
            .testTag(TestTags.STANDBY_SCREEN),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("STANDBY", color = Color.White.copy(alpha = 0.35f), modifier = Modifier.testTag(TestTags.STATUS_MODE))
        OutlinedButton(onClick = onBellNow, modifier = Modifier.testTag(TestTags.BELL_NOW_BUTTON)) {
            Text("Bell now", color = Color.White.copy(alpha = 0.6f))
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.testTag(TestTags.STOP_BUTTON)) {
            Text("Stop", color = Color.White.copy(alpha = 0.6f))
        }
    }
}
