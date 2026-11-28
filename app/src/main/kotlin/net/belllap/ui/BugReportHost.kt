package net.belllap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import net.belllap.bugreport.BugReportController
import net.belllap.bugreport.QueuedReport
import net.belllap.bugreport.ReportStep
import net.belllap.bugreport.UploadProgress

/** Routes between the form, consent and status screens for one [BugReportController]. */
@Composable
fun BugReportHost(
    controller: BugReportController,
    reports: StateFlow<List<QueuedReport>>,
    progress: StateFlow<Map<String, UploadProgress>>,
    onOpenIssue: (String) -> Unit,
    onClose: () -> Unit,
) {
    val state by controller.ui.collectAsState()
    val queued by reports.collectAsState()
    val uploading by progress.collectAsState()
    val close = {
        controller.reset()
        onClose()
    }
    BackHandler {
        when (state.step) {
            ReportStep.CONSENT -> controller.back()
            else -> close()
        }
    }
    when (state.step) {
        ReportStep.FORM -> BugReportScreen(
            ui = state.form.copy(queuedCount = queued.size),
            onDescriptionChange = controller::setDescription,
            onRecordBell = controller::startBell,
            onStopBell = controller::stopBell,
            onClearBell = controller::clearBell,
            onToggleVideo = controller::toggleVideo,
            onCenterChange = controller::setCenter,
            onContinue = controller::review,
            onOpenQueue = controller::showStatus,
            onCancel = close,
        )
        ReportStep.CONSENT -> state.consent?.let {
            BugReportConsentScreen(it, onSend = controller::send, onBack = controller::back)
        }
        ReportStep.STATUS -> BugReportStatusScreen(
            rows = queued.map { reportRow(it, uploading[it.localId]) },
            onRetry = controller::retry,
            onDiscard = controller::discard,
            onOpenIssue = onOpenIssue,
            onNewReport = controller::newReport,
            onClose = close,
        )
    }
}
