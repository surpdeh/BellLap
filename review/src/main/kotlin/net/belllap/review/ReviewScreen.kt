package net.belllap.review

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.belllap.core.model.Acquisition
import net.belllap.core.model.AcquisitionStore

/**
 * Review (F19): the acquisition list, and the player for the one opened. It reads and writes only the acquisition
 * files in [store], and leaves the session, camera and service alone, so it runs while the session is ARMED.
 */
@Composable
fun ReviewScreen(store: AcquisitionStore, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Acquisition>>(emptyList()) }
    val scope = rememberCoroutineScope()
    BackHandler { if (openId != null) openId = null else onClose() }
    val io = rememberStoreDispatcher()

    suspend fun reload() {
        items = withContext(io) { store.list() }
    }
    LaunchedEffect(openId) { if (openId == null) reload() }
    val open = openId?.let { id -> items.firstOrNull { it.sidecar.id == id } ?: store.load(id) }
    if (open == null) {
        ReviewListScreen(
            items = items.map {
                ReviewItem(
                    it.sidecar.id,
                    it.sidecar.name,
                    "${it.sidecar.crossings.size} finishers, ${it.sidecar.fps} fps",
                )
            },
            onOpen = { openId = it },
            onRename = { id, name ->
                scope.launch {
                    withContext(io) { store.rename(id, name) }
                    reload()
                }
            },
            onDelete = { id ->
                scope.launch {
                    withContext(io) { store.delete(id) }
                    reload()
                }
            },
            onClose = onClose,
            modifier = modifier,
        )
    } else {
        ReviewPlayerHost(open, store, io, onBack = { openId = null }, modifier = modifier)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReviewPlayerHost(
    acquisition: Acquisition,
    store: AcquisitionStore,
    io: CoroutineDispatcher,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(acquisition.video) { ReviewPlayer(context, acquisition.video) }
    DisposableEffect(player) { onDispose(player::release) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    val state = remember(player) {
        ReviewState(
            sidecar = acquisition.sidecar,
            durationUs = null,
            seekTo = player::seekToUs,
            onChange = { edited -> scope.launch(io) { store.save(edited) } },
        )
    }
    LaunchedEffect(player.durationUs) {
        if (player.durationUs != C.TIME_UNSET) state.onDuration(player.durationUs)
    }
    ReviewPlayerScreen(state, onClose = onBack, modifier = modifier) {
        Box(Modifier.fillMaxSize()) { ContentFrame(player = player.exo, surfaceType = SURFACE_TYPE_TEXTURE_VIEW) }
    }
}

/** One thread for all store access, so a save is never overtaken by a later list or save. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberStoreDispatcher(): CoroutineDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }
