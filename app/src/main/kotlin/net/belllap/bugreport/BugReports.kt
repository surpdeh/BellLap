package net.belllap.bugreport

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.StateFlow
import net.belllap.BuildConfig
import java.io.File
import java.io.IOException
import java.util.UUID

/** Routes each call to the override (debug tests) or the real client, so the queue is built once per process. */
private class SwitchableApi(private val current: () -> ReportApi) : ReportApi {
    override suspend fun create(
        appCheckToken: String,
        request: CreateRequest,
    ) = current().create(appCheckToken, request)

    override suspend fun put(target: UploadTarget, file: File, onProgress: (Long) -> Unit) =
        current().put(target, file, onProgress)

    override suspend fun submit(appCheckToken: String, reportId: String) = current().submit(appCheckToken, reportId)
}

/** The app's bug-report services: the queue under `filesDir/bugreports`, the Worker client and the install id. */
class BugReports(
    private val context: Context,
    private val appCheck: AppCheckTokenSource,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val real: ReportApi? = BuildConfig.REPORTS_URL.takeIf {
        it.isNotEmpty()
    }?.let { HttpReportApi(it.trimEnd('/')) }

    /** False in a build with no Worker URL or Firebase API key: Send stays disabled with a visible explanation. */
    val configured: Boolean
        get() = ReportOverrides.api != null || (real != null && BuildConfig.FIREBASE_API_KEY.isNotEmpty())

    val queue: ReportQueue by lazy {
        val api = SwitchableApi { ReportOverrides.api ?: real ?: throw IOException("Bug reports are not configured") }
        ReportQueue(
            ReportStore(File(context.filesDir, "bugreports")),
            api,
            { (ReportOverrides.tokens ?: appCheck).token() },
            nowMs,
        )
    }

    val reports: StateFlow<List<QueuedReport>> get() = queue.reports

    fun reportContext(): ReportContext {
        val prefs = context.getSharedPreferences("bugreport", Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_INSTALL_ID, it) }
        }
        return ReportContext(
            installId = id,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})".take(MAX_VERSION),
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}".take(MAX_DEVICE),
        )
    }

    private companion object {
        const val KEY_INSTALL_ID = "installId"
        const val MAX_VERSION = 32
        const val MAX_DEVICE = 128
    }
}

/** Placeholder until Firebase App Check is wired; every upload then waits and retries. */
object NotConfiguredAppCheck : AppCheckTokenSource {
    override suspend fun token(): String = throw IOException("App Check is not configured in this build")
}
