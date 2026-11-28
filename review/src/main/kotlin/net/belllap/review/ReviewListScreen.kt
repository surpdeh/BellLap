package net.belllap.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The acquisition list (F19): open, rename or delete a recording. */
@Composable
fun ReviewListScreen(
    items: List<ReviewItem>,
    onOpen: (String) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<ReviewItem?>(null) }
    var deleting by remember { mutableStateOf<ReviewItem?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .testTag(ReviewTags.LIST_SCREEN),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Review", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.testTag(ReviewTags.CLOSE),
            ) { Text("Close", color = Color.White) }
        }
        if (items.isEmpty()) {
            Text(
                "No recordings yet.",
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.testTag(ReviewTags.LIST_EMPTY),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onOpen(item.id) }
                        .padding(12.dp)
                        .testTag(ReviewTags.ITEM_PREFIX + item.id),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(
                            item.detail,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        renaming = item
                    }, modifier = Modifier.testTag(ReviewTags.RENAME_PREFIX + item.id)) { Text("Rename") }
                    TextButton(onClick = {
                        deleting = item
                    }, modifier = Modifier.testTag(ReviewTags.DELETE_PREFIX + item.id)) { Text("Delete") }
                }
            }
        }
    }
    renaming?.let { item ->
        RenameDialog(item, onConfirm = { onRename(item.id, it) }, onDismiss = { renaming = null })
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("The video and its finisher marks are removed from this phone.") },
            confirmButton = {
                Button(onClick = {
                    onDelete(item.id)
                    deleting = null
                }, modifier = Modifier.testTag(ReviewTags.DELETE_CONFIRM)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenameDialog(item: ReviewItem, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                singleLine = true,
                modifier = Modifier.testTag(ReviewTags.NAME_FIELD),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(ReviewTags.NAME_CONFIRM),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
