package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TestTags.SETUP_SCREEN),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TestTags.CAMERA_PREVIEW_PLACEHOLDER),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Camera preview",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        StatusOverlay(
            mode = "SETUP",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )
    }
}

@Composable
private fun StatusOverlay(mode: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TestTags.STATUS_OVERLAY),
    ) {
        Text(
            text = mode,
            color = Color.Black,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(TestTags.STATUS_MODE),
        )
    }
}
