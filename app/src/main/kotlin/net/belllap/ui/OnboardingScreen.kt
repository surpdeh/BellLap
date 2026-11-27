package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** What the onboarding screen needs to know. Notifications are always "granted" below API 33. */
data class OnboardingStatus(
    val notificationsGranted: Boolean,
    val cameraMicGranted: Boolean,
    val batteryExempt: Boolean,
) {
    /** The mic and camera are required to run a session; the rest only make it more reliable. */
    val canContinue: Boolean get() = cameraMicGranted
    val complete: Boolean get() = notificationsGranted && cameraMicGranted && batteryExempt
}

const val OEM_TIPS_URL = "https://dontkillmyapp.com"

@Composable
fun OnboardingScreen(
    status: OnboardingStatus,
    onRequestNotifications: () -> Unit,
    onRequestCameraMic: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemTips: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TestTags.ONBOARDING_SCREEN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "A session can run for an hour or more with the screen dimmed. These settings keep it alive.",
            color = Color.White.copy(alpha = 0.8f),
        )
        Step(
            "Notifications",
            "Shows the ongoing session notification with Bell now, Record now, Stop and Exit.",
            status.notificationsGranted,
            TestTags.STEP_NOTIFICATIONS,
            onRequestNotifications,
        )
        Step(
            "Microphone and camera",
            "Needed to hear the bell and see the flag and finish line.",
            status.cameraMicGranted,
            TestTags.STEP_CAMERA_MIC,
            onRequestCameraMic,
        )
        Step(
            "Battery optimisation",
            "Exempt BellLap so the phone does not stop it during a long standby.",
            status.batteryExempt,
            TestTags.STEP_BATTERY,
            onRequestBatteryExemption,
        )
        OutlinedButton(onClick = onOpenOemTips, modifier = Modifier.testTag(TestTags.OEM_TIPS_BUTTON)) {
            Text("Phone-specific tips (dontkillmyapp.com)")
        }
        Button(
            onClick = onContinue,
            enabled = status.canContinue,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ONBOARDING_CONTINUE),
        ) { Text(if (status.complete) "Continue" else "Continue anyway") }
    }
}

@Composable
private fun Step(title: String, body: String, done: Boolean, tag: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(body, color = Color.White.copy(alpha = 0.7f))
        }
        if (done) {
            Text("Done", color = Color(0xFF7FD67F), modifier = Modifier.testTag("${tag}_done"))
        } else {
            Button(onClick = onAction, modifier = Modifier.testTag("${tag}_action")) { Text("Allow") }
        }
    }
}
