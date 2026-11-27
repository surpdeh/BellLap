package net.belllap

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Saves named screenshots to the app's `files/evidence/` directory so CI can pull them
 * (`scripts/ci/emulator-e2e.sh`) and publish them in the PR evidence comment.
 */
object TestEvidence {
    private const val TAG = "TestEvidence"
    private const val DIR = "evidence"

    /** Directory that CI pulls from: `<app files dir>/evidence`. */
    fun directory(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, DIR).apply { mkdirs() }

    /** Captures the whole screen and saves it as `<name>.png`. Returns the file. */
    fun screenshot(name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "Evidence name must be [A-Za-z0-9_-]+, was '$name'" }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "UiAutomation.takeScreenshot() returned null"
        }
        check(!bitmap.isBlack()) {
            "Screenshot '$name' is entirely black: display off, locked, or a headless emulator image"
        }
        val file = File(directory(), "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "Saved ${file.absolutePath}")
        return file
    }

    private fun Bitmap.isBlack(): Boolean {
        val step = SAMPLE_STEP
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                if (getPixel(x, y) and RGB_MASK != 0) return false
            }
        }
        return true
    }

    private const val SAMPLE_STEP = 16
    private const val RGB_MASK = 0x00FFFFFF
}
