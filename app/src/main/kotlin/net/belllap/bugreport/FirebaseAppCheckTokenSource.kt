package net.belllap.bugreport

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase App Check with the Play Integrity provider, for the project the Worker verifies against
 * (`backend/worker/wrangler.jsonc`). A failure to get a token is an [IOException], so the upload backs off and retries.
 */
class FirebaseAppCheckTokenSource(private val context: Context, private val apiKey: String) : AppCheckTokenSource {
    private val appCheck: FirebaseAppCheck by lazy {
        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(APP_ID)
            .setProjectId(PROJECT_NUMBER)
            .setGcmSenderId(PROJECT_NUMBER)
            .build()
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(context, options, APP_NAME)
        FirebaseAppCheck.getInstance(app).also {
            it.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }

    override suspend fun token(): String = suspendCancellableCoroutine { cont ->
        try {
            appCheck.getAppCheckToken(false)
                .addOnSuccessListener { cont.resume(it.token) }
                .addOnFailureListener {
                    cont.resumeWithException(
                        IOException("App Check token failed: ${it.message}", it),
                    )
                }
        } catch (e: IllegalStateException) {
            cont.resumeWithException(IOException("App Check is unavailable: ${e.message}", e))
        }
    }

    private companion object {
        const val APP_NAME = "belllap-reports"
        const val PROJECT_NUMBER = "611005785266"
        const val APP_ID = "1:611005785266:android:e0c5e11a28e61091ee15ab"
    }
}
