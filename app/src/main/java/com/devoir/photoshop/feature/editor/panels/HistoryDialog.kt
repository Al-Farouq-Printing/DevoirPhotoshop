package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.history.HistoryUiState

/** Every step, oldest first. The current state is highlighted; dimmed steps are undone (redo-able). Tap to jump. */
@Composable
fun HistoryDialog(state: HistoryUiState, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    val items = remember(state.labels) { listOf("Original state") + state.labels }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem((state.currentIndex - 2).coerceAtLeast(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            Column {
                if (state.isModified) {
                    Text("Unsaved changes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), state = listState) {
                    itemsIndexed(items) { i, label ->
                        val current = i == state.currentIndex
                        val undone = i > state.currentIndex
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (current) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onJump(i) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (current) Icon(Icons.Default.Check, "Current state", Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                color = if (undone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
