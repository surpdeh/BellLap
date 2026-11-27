package net.belllap.debug

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.belllap.BellLapApp
import net.belllap.core.replay.Detectors
import net.belllap.core.replay.ReplaySpeed
import java.io.File

/** Debug-only entry point drawn over the setup screen: opens the Replay screen (F24). */
@Composable
fun DebugEntry(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (open) {
            ReplayScreen(onClose = { open = false })
        } else {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .testTag(ReplayTags.OPEN),
            ) { Text("Replay (debug)") }
        }
    }
}

@Composable
private fun ReplayScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ReplayController((context.applicationContext as BellLapApp).eventLog) }
    val scope = rememberCoroutineScope()
    var fast by remember { mutableStateOf(true) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var audio by remember { mutableStateOf<Uri?>(null) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it ?: video }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { audio = it ?: audio }
    val speed = if (fast) ReplaySpeed.FAST else ReplaySpeed.REAL_TIME

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .testTag(ReplayTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                controller.stateLabel,
                color = Color.Black,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .background(Color.White, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(ReplayTags.STATE),
            )
            Text(controller.status, color = Color.White, modifier = Modifier.testTag(ReplayTags.STATUS))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(fast, { fast = it }, enabled = !controller.running, modifier = Modifier.testTag(ReplayTags.SPEED))
            Text(
                if (fast) "As fast as possible" else "Real time",
                color = Color.White,
                modifier = Modifier.padding(8.dp),
            )
        }
        Button(
            onClick = { scope.launch { controller.replay({ ReplayFixtures.raceSynth(context.replayDir()) }, speed) } },
            enabled = !controller.running,
            modifier = Modifier.testTag(ReplayTags.RUN_RACE_SYNTH),
        ) { Text("Replay race_synth fixture") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pickVideo.launch(arrayOf("video/*")) }, Modifier.testTag(ReplayTags.PICK_VIDEO)) {
                Text(if (video == null) "Pick MP4" else "MP4 picked")
            }
            OutlinedButton({
                pickAudio.launch(arrayOf("audio/*", "application/octet-stream"))
            }, Modifier.testTag(ReplayTags.PICK_AUDIO)) {
                Text(if (audio == null) "Pick WAV" else "WAV picked")
            }
        }
        Button(
            onClick = {
                val v = checkNotNull(video)
                val a = checkNotNull(audio)
                scope.launch { controller.replay({ context.copyPicked(v, a) }, speed) }
            },
            enabled = !controller.running && video != null && audio != null,
            modifier = Modifier.testTag(ReplayTags.RUN_FILES),
        ) { Text("Replay picked files (no detectors)") }
        LazyColumn(Modifier.weight(1f).testTag(ReplayTags.LOG)) {
            items(
                controller.lines.toList(),
            ) { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClose, Modifier.testTag(ReplayTags.CLOSE)) { Text("Close") }
    }
}

private fun Context.replayDir() = File(cacheDir, "replay").apply { mkdirs() }

/** SAF gives a content Uri; MediaExtractor and the WAV reader want files, so copy into the cache. */
private fun Context.copyPicked(video: Uri, audio: Uri): ReplayInput {
    fun copy(uri: Uri, name: String) = File(replayDir(), name).also { out ->
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    }
    return ReplayInput("picked files", copy(video, "picked.mp4"), copy(audio, "picked.wav"), Detectors.NONE)
}
