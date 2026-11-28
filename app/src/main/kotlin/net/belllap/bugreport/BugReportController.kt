package net.belllap.bugreport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.belllap.ui.BellUi
import net.belllap.ui.BugReportConsentUi
import net.belllap.ui.BugReportFormUi
import net.belllap.ui.CandidateUi
import net.belllap.ui.ConsentItemUi
import net.belllap.ui.VideoRowUi
import net.belllap.ui.formatBytes
import java.io.File

enum class ReportStep { FORM, CONSENT, STATUS }

/** What [BugReportController] shows now. Only the active step's state is meaningful. */
data class BugReportUiState(
    val step: ReportStep = ReportStep.FORM,
    val form: BugReportFormUi = BugReportFormUi(),
    val consent: BugReportConsentUi? = null,
)

/**
 * The state behind the three report screens. Nothing is kept between reports: [scratch] is emptied when a form opens
 * and again after Send or Cancel. Nothing is uploaded before [send].
 */
@Suppress("LongParameterList", "TooManyFunctions") // one method per UI event
class BugReportController(
    private val scope: CoroutineScope,
    private val bell: BellClipRecorder,
    private val trimmer: VideoTrimmer,
    private val bundler: TraceBundler,
    private val queue: ReportQueue,
    private val scratch: File,
    private val captured: List<CapturedVideo>,
    private val reportContext: () -> ReportContext,
    private val configured: () -> Boolean,
    private val schedule: () -> Unit,
    bellAvailable: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val selected = linkedMapOf<String, Long>()
    private val durations = mutableMapOf<String, Long>()
    private var bellFile: File? = null
    private var recording: Job? = null
    private var pending: ReportDraft? = null

    private val state = MutableStateFlow(BugReportUiState(form = BugReportFormUi(bellAvailable = bellAvailable)))
    val ui: StateFlow<BugReportUiState> = state

    init {
        scratch.deleteRecursively()
        scratch.mkdirs()
        scope.launch {
            durations += withContext(io) { captured.associate { it.name to durationOf(it) } }
            publishVideos()
        }
        publishVideos()
    }

    private suspend fun durationOf(video: CapturedVideo): Long = runCatching {
        trimmer.durationUs(video.file)
    }.getOrDefault(0L)

    private fun updateForm(change: BugReportFormUi.() -> BugReportFormUi) =
        state.update { it.copy(form = it.form.change()) }

    private fun publishVideos() = updateForm {
        copy(
            videos = captured.map { v ->
                VideoRowUi(
                    name = v.name,
                    sizeText = formatBytes(v.sizeBytes),
                    selected = v.name in selected,
                    centerUs = selected[v.name] ?: v.candidates.firstOrNull()?.centerUs ?: 0L,
                    durationUs = durations[v.name] ?: 0L,
                    candidates = v.candidates.map { CandidateUi(it.label, it.centerUs) },
                )
            },
        )
    }

    fun setDescription(text: String) = updateForm { copy(description = text, problems = emptyList()) }

    fun startBell() {
        if (recording?.isActive == true) return
        val out = File(scratch, "bell.wav")
        updateForm { copy(bell = BellUi.Recording) }
        recording = scope.launch {
            attempt { bell.record(out) }
                .onSuccess {
                    bellFile = out
                    updateForm { copy(bell = BellUi.Recorded(it / MS_PER_SECOND)) }
                }
                .onFailure {
                    updateForm { copy(bell = BellUi.None, problems = listOf("Could not record: ${it.message}")) }
                }
        }
    }

    fun stopBell() = bell.stop()

    fun clearBell() {
        bellFile?.delete()
        bellFile = null
        updateForm { copy(bell = BellUi.None) }
    }

    fun toggleVideo(name: String) {
        val video = captured.firstOrNull { it.name == name } ?: return
        if (name in selected) {
            selected.remove(name)
        } else if (selected.size < Caps.of(AttachmentKind.VIDEO).maxCount) {
            selected[name] = video.candidates.firstOrNull()?.centerUs ?: durations[name]?.div(2) ?: 0L
        }
        publishVideos()
    }

    fun setCenter(name: String, centerUs: Long) {
        if (name in selected) selected[name] = centerUs
        publishVideos()
    }

    /** Builds the attachments (bundling traces, trimming videos), validates, and moves to the consent screen. */
    fun review() {
        updateForm { copy(preparing = true, problems = emptyList()) }
        scope.launch {
            val draft = attempt { withContext(io) { buildDraft() } }.getOrElse {
                updateForm { copy(preparing = false, problems = listOf("Could not prepare the report: ${it.message}")) }
                return@launch
            }
            val problems = draft.validate()
            if (problems.isNotEmpty()) {
                updateForm { copy(preparing = false, problems = problems.map { it.message }) }
            } else {
                pending = draft
                val items = draft.attachments.map { ConsentItemUi(it.name, it.kind, it.size) }
                updateForm { copy(preparing = false) }
                state.update {
                    it.copy(
                        step = ReportStep.CONSENT,
                        consent = BugReportConsentUi(draft.description, items, configured()),
                    )
                }
            }
        }
    }

    private suspend fun buildDraft(): ReportDraft {
        val out = File(scratch, "attachments").apply { deleteRecursively() }
        out.mkdirs()
        val attachments = bundler.bundle(out).toMutableList()
        bellFile?.takeIf { it.isFile }?.let {
            attachments += Attachment("bell.wav", AttachmentKind.BELL, "audio/wav", it)
        }
        selected.entries.forEachIndexed { i, (name, centerUs) ->
            val video = captured.first { it.name == name }
            val window = TrimWindow.around(centerUs, durations[name] ?: 0L)
            val clip = File(out, "clip-${i + 1}.mp4")
            trimmer.trim(video.file, window, clip)
            attachments += Attachment(clip.name, AttachmentKind.VIDEO, "video/mp4", clip)
        }
        return ReportDraft(state.value.form.description.trim(), attachments)
    }

    fun back() {
        pending = null
        state.update { it.copy(step = ReportStep.FORM, consent = null) }
    }

    fun send() {
        val draft = pending ?: return
        if (!configured() || state.value.consent?.sending == true) return
        state.update { it.copy(consent = it.consent?.copy(sending = true)) }
        scope.launch {
            when (val result = queue.enqueue(draft, reportContext())) {
                is EnqueueResult.Queued -> {
                    schedule()
                    reset()
                    state.update { it.copy(step = ReportStep.STATUS, consent = null) }
                }
                is EnqueueResult.Invalid -> {
                    state.update { it.copy(step = ReportStep.FORM, consent = null) }
                    updateForm { copy(problems = result.problems.map { it.message }) }
                }
            }
        }
    }

    fun newReport() {
        reset()
        state.update { it.copy(step = ReportStep.FORM) }
    }

    fun showStatus() = state.update { it.copy(step = ReportStep.STATUS) }

    fun retry(localId: String) {
        queue.retry(localId)
        schedule()
    }

    fun discard(localId: String) = queue.discard(localId)

    /** Forgets the form: description, recording, selections and every scratch file. */
    fun reset() {
        recording?.cancel()
        selected.clear()
        bellFile = null
        pending = null
        scratch.deleteRecursively()
        scratch.mkdirs()
        state.update { it.copy(form = BugReportFormUi(bellAvailable = it.form.bellAvailable)) }
        publishVideos()
    }

    /** Platform media and audio calls throw assorted runtime exceptions; the user sees the message, not a crash. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private companion object {
        const val MS_PER_SECOND = 1000f
    }
}
