package net.belllap.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Release builds have no replay entry. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugEntry(modifier: Modifier = Modifier) = Unit
