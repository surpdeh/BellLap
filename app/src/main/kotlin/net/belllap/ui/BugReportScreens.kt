package net.belllap.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.belllap.bugreport.Caps
import net.belllap.bugreport.TrimWindow

private const val US_PER_SECOND = 1_000_000f
private const val SECONDS_PER_MINUTE = 60

private fun clock(us: Long): String {
    val total = (us / US_PER_SECOND).toInt()
    return "%d:%02d".format(total / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
}

@Composable
private fun ScreenColumn(tag: String, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Suppress("LongParameterList")
@Composable
fun BugReportScreen(
    ui: BugReportFormUi,
    onDescriptionChange: (String) -> Unit,
    onRecordBell: () -> Unit,
    onStopBell: () -> Unit,
    onClearBell: () -> Unit,
    onToggleVideo: (String) -> Unit,
    onCenterChange: (String, Long) -> Unit,
    onContinue: () -> Unit,
    onOpenQueue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(TestTags.BUG_FORM_SCREEN, modifier) {
        Text("Report a problem", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = ui.description,
            onValueChange = { onDescriptionChange(it.take(Caps.MAX_DESCRIPTION)) },
            label = { Text("What went wrong?") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BUG_DESCRIPTION),
        )
        Text(
            "The trace bundle (event log, logs, camera sidecars, device capabilities) is attached automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )
        BellSection(ui, onRecordBell, onStopBell, onClearBell)
        VideoSection(ui, onToggleVideo, onCenterChange)
        if (ui.problems.isNotEmpty()) {
            Column(modifier = Modifier.testTag(TestTags.BUG_PROBLEMS)) {
                ui.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Button(
            onClick = onContinue,
            enabled = ui.description.isNotBlank() && !ui.preparing && ui.bell != BellUi.Recording,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BUG_CONTINUE),
        ) { Text(if (ui.preparing) "Preparing..." else "Review what will be sent") }
        if (ui.queuedCount > 0) {
            OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth().testTag(TestTags.BUG_OPEN_QUEUE)) {
                Text("Sent and waiting reports (${ui.queuedCount})")
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag(TestTags.BUG_CANCEL)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun BellSection(ui: BugReportFormUi, onRecord: () -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    Text("Bell recording (optional)", style = MaterialTheme.typography.titleMedium)
    when {
        !ui.bellAvailable -> Text(
            "Not available while a session is running or without microphone access.",
            modifier = Modifier.testTag(TestTags.BUG_BELL_STATUS),
        )
        ui.bell == BellUi.None -> Button(onClick = onRecord, modifier = Modifier.testTag(TestTags.BUG_BELL_RECORD)) {
            Text("Record the bell (up to 10 s)")
        }
        ui.bell == BellUi.Recording -> {
            Text("Recording...", modifier = Modifier.testTag(TestTags.BUG_BELL_STATUS))
            Button(onClick = onStop, modifier = Modifier.testTag(TestTags.BUG_BELL_STOP)) { Text("Stop") }
        }
        ui.bell is BellUi.Recorded -> {
            Text("Recorded %.1f s".format(ui.bell.seconds), modifier = Modifier.testTag(TestTags.BUG_BELL_STATUS))
            OutlinedButton(onClick = onClear, modifier = Modifier.testTag(TestTags.BUG_BELL_CLEAR)) { Text("Remove") }
        }
    }
}

@Composable
private fun VideoSection(ui: BugReportFormUi, onToggle: (String) -> Unit, onCenter: (String, Long) -> Unit) {
    Text(
        "Videos (optional, up to ${Caps.of(net.belllap.bugreport.AttachmentKind.VIDEO).maxCount})",
        style = MaterialTheme.typography.titleMedium,
    )
    if (ui.videos.isEmpty()) Text("No captured videos on this phone.")
    ui.videos.forEach { v ->
        Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.BUG_VIDEO_PREFIX + v.name)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${v.name} (${v.sizeText})", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onToggle(v.name) }) { Text(if (v.selected) "Remove" else "Attach") }
            }
            if (v.selected) {
                val window = TrimWindow.around(v.centerUs, v.durationUs)
                Text("Clip ${clock(window.startUs)} to ${clock(window.endUs)}, around ${clock(v.centerUs)}")
                v.candidates.forEach { c ->
                    FilterChip(
                        selected = c.centerUs == v.centerUs,
                        onClick = { onCenter(v.name, c.centerUs) },
                        label = { Text("${c.label} ${clock(c.centerUs)}") },
                    )
                }
                Slider(
                    value = v.centerUs / US_PER_SECOND,
                    onValueChange = { onCenter(v.name, (it * US_PER_SECOND).toLong()) },
                    valueRange = 0f..maxOf(1f, v.durationUs / US_PER_SECOND),
                    modifier = Modifier.testTag(TestTags.BUG_VIDEO_SLIDER_PREFIX + v.name),
                )
            }
        }
    }
}

@Composable
fun BugReportConsentScreen(
    ui: BugReportConsentUi,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(TestTags.CONSENT_SCREEN, modifier) {
        Text("Review before sending", style = MaterialTheme.typography.headlineSmall)
        Text("Description", style = MaterialTheme.typography.titleMedium)
        Text(ui.description)
        Text("Files", style = MaterialTheme.typography.titleMedium)
        ui.items.forEach {
            Text(
                "${it.name}: ${kindLabel(it.kind)}, ${formatBytes(it.bytes)}",
                modifier = Modifier.testTag(TestTags.CONSENT_ITEM_PREFIX + it.name),
            )
        }
        Text("Total ${formatBytes(ui.totalBytes)}", modifier = Modifier.testTag(TestTags.CONSENT_TOTAL))
        if (ui.hasVideo) {
            Text(
                "Videos can show identifiable people, such as riders and spectators.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.CONSENT_VIDEO_WARNING),
            )
        }
        Text(
            "Your description is filed as a public GitHub issue, so anyone can read it. The files above go to a " +
                "private store that only the BellLap developers can read; they are never attached to the issue. " +
                "Nothing is sent until you tap Send.",
        )
        if (!ui.configured) {
            Text(
                "Bug reports are not configured in this build",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTags.CONSENT_NOT_CONFIGURED),
            )
        }
        Button(
            onClick = onSend,
            enabled = ui.configured && !ui.sending,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.CONSENT_SEND),
        ) { Text(if (ui.sending) "Sending..." else "Send") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag(TestTags.CONSENT_BACK)) {
            Text("Back")
        }
    }
}

@Composable
fun BugReportStatusScreen(
    rows: List<ReportRowUi>,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onOpenIssue: (String) -> Unit,
    onNewReport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(TestTags.STATUS_SCREEN, modifier) {
        Text("Bug reports", style = MaterialTheme.typography.headlineSmall)
        if (rows.isEmpty()) Text("Nothing waiting and nothing sent.")
        rows.forEach { row ->
            Column(
                modifier = Modifier.fillMaxWidth().testTag(TestTags.STATUS_ROW_PREFIX + row.localId),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.detail)
                row.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.canRetry) {
                        Button(
                            onClick = { onRetry(row.localId) },
                            modifier = Modifier.testTag(TestTags.STATUS_RETRY_PREFIX + row.localId),
                        ) {
                            Text("Retry")
                        }
                    }
                    row.issueUrl?.let { url ->
                        Button(
                            onClick = { onOpenIssue(url) },
                            modifier = Modifier.testTag(TestTags.STATUS_ISSUE_PREFIX + row.localId),
                        ) {
                            Text("Open issue")
                        }
                    }
                    OutlinedButton(onClick = {
                        onDiscard(row.localId)
                    }, modifier = Modifier.testTag(TestTags.STATUS_DISCARD_PREFIX + row.localId)) {
                        Text(if (row.status == ReportStatus.FILED) "Dismiss" else "Discard")
                    }
                }
            }
        }
        Button(onClick = onNewReport, modifier = Modifier.fillMaxWidth().testTag(TestTags.STATUS_NEW_REPORT)) {
            Text("Report another problem")
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.STATUS_CLOSE),
        ) { Text("Close") }
    }
}
