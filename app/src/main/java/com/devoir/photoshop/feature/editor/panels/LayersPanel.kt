package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.BACKGROUND_ID
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.feature.editor.LayerActions
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import com.devoir.photoshop.feature.editor.canvas.toColorFilter
import com.devoir.photoshop.model.BackgroundType
import kotlin.math.roundToInt

fun LayerType.icon(): ImageVector = when (this) {
    LayerType.Image -> Icons.Default.Image
    LayerType.Paint -> Icons.Default.Brush
    LayerType.Text -> Icons.Default.TextFields
    LayerType.Shape -> Icons.Default.Category
    LayerType.Adjustment -> Icons.Default.Tune
}

@Composable
fun LayersPanel(document: CanvasDocument, actions: LayerActions, modifier: Modifier = Modifier) {
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Layer?>(null) }
    var deleteTarget by remember { mutableStateOf<Layer?>(null) }
    val checker = rememberCheckerBrush(5.dp)

    val selected = document.selectedLayer
    val idx = if (selected == null) -1 else document.layers.indexOfFirst { it.id == selected.id }
    val last = document.layers.lastIndex

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add layer") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Paint layer") }, leadingIcon = { Icon(Icons.Default.Brush, null) },
                        onClick = { addMenu = false; actions.onAddPaint() },
                    )
                    DropdownMenuItem(
                        text = { Text("Image layer…") }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                        onClick = { addMenu = false; actions.onAddImage() },
                    )
                    DropdownMenuItem(
                        text = { Text("Text layer…") }, leadingIcon = { Icon(Icons.Default.TextFields, null) },
                        onClick = { addMenu = false; actions.onAddText() },
                    )
                    listOf(ShapeType.Rectangle, ShapeType.RoundedRectangle, ShapeType.Circle, ShapeType.Ellipse, ShapeType.Line).forEach { st ->
                        DropdownMenuItem(
                            text = { Text(st.label) }, leadingIcon = { Icon(Icons.Default.Category, null) },
                            onClick = { addMenu = false; actions.onAddShape(st) },
                        )
                    }
                }
            }
            IconButton(onClick = { selected?.let { actions.onDuplicate(it.id) } }, enabled = selected != null) {
                Icon(Icons.Default.ContentCopy, "Duplicate layer")
            }
            IconButton(onClick = { deleteTarget = selected }, enabled = selected != null && !selected.locked) {
                Icon(Icons.Default.Delete, "Delete layer")
            }
            Box {
                IconButton(onClick = { moreMenu = true }, enabled = selected != null) { Icon(Icons.Default.MoreVert, "Layer options") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { moreMenu = false; renameTarget = selected },
                    )
                    DropdownMenuItem(
                        text = { Text("Bring to front") }, leadingIcon = { Icon(Icons.Default.VerticalAlignTop, null) },
                        enabled = idx in 0 until last,
                        onClick = { moreMenu = false; selected?.let { actions.onMove(it.id, MoveDirection.Top) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Move up") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                        enabled = idx in 0 until last,
                        onClick = { moreMenu = false; selected?.let { actions.onMove(it.id, MoveDirection.Up) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                        enabled = idx > 0,
                        onClick = { moreMenu = false; selected?.let { actions.onMove(it.id, MoveDirection.Down) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Send to back") }, leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, null) },
                        enabled = idx > 0,
                        onClick = { moreMenu = false; selected?.let { actions.onMove(it.id, MoveDirection.Bottom) } },
                    )
                }
            }
        }

        // Opacity of the selected layer (real, applied to the canvas immediately)
        val canEditOpacity = selected != null && !selected.locked
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = selected?.opacity ?: 1f,
                onValueChange = { v -> selected?.let { actions.onSetOpacity(it.id, v) } },
                enabled = canEditOpacity,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${((selected?.opacity ?: 1f) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            items(document.layers.asReversed(), key = { it.id }) { layer ->
                LayerRow(layer, layer.id == document.selectedLayerId, checker, actions)
            }
            item(key = BACKGROUND_ID) { BackgroundRow(document, checker, actions) }
        }
    }

    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    isError = text.isBlank(),
                    supportingText = if (text.isBlank()) ({ Text("Name can't be empty") }) else null,
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { actions.onRename(target.id, text); renameTarget = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete layer?") },
            text = { Text("\"${target.name}\" will be removed. This can't be undone yet.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(target.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun rowColor(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent

@Composable
private fun LayerRow(layer: Layer, selected: Boolean, checker: Brush, actions: LayerActions) {
    Row(
        Modifier.fillMaxWidth()
            .background(rowColor(selected))
            .clickable { actions.onSelect(layer.id) }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(layer, checker)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(layer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(layer.type.icon(), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    "${layer.type.label}${if ((layer.content as? LayerContent.Image)?.adjustments?.isDefault == false) " (adjusted)" else ""} · ${(layer.opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { actions.onSetLocked(layer.id, !layer.locked) }) {
            Icon(if (layer.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (layer.locked) "Unlock layer" else "Lock layer")
        }
        IconButton(onClick = { actions.onSetVisible(layer.id, !layer.visible) }) {
            Icon(if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (layer.visible) "Hide layer" else "Show layer")
        }
    }
}

@Composable
private fun BackgroundRow(document: CanvasDocument, checker: Brush, actions: LayerActions) {
    val selected = document.selectedLayerId == BACKGROUND_ID
    val cfg = document.config
    Row(
        Modifier.fillMaxWidth()
            .background(rowColor(selected))
            .clickable { actions.onSelect(BACKGROUND_ID) }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(checker)) {
            if (document.background.visible && cfg.backgroundType != BackgroundType.Transparent) {
                Box(
                    Modifier.fillMaxSize().background(
                        if (cfg.backgroundType == BackgroundType.White) Color.White else Color(cfg.backgroundColor),
                    ),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Background", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Fill · ${cfg.backgroundType.label}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.Lock, "Background is locked", Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { actions.onSetVisible(BACKGROUND_ID, !document.background.visible) }) {
            Icon(
                if (document.background.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                if (document.background.visible) "Hide background" else "Show background",
            )
        }
    }
}

@Composable
private fun Thumbnail(layer: Layer, checker: Brush) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(checker),
        contentAlignment = Alignment.Center,
    ) {
        val res = layer.content.resource
        val content = layer.content
        if (content is LayerContent.Text) {
            TextThumb(content.spec, Modifier.fillMaxSize().padding(3.dp))
        } else if (content is LayerContent.Shape) {
            ShapeThumb(content.spec, layer.width * layer.transform.scaleX, layer.height * layer.transform.scaleY, Modifier.fillMaxSize().padding(4.dp))
        } else if (res != null && !res.isReleased) {
            val image = remember(res, layer.content.revision) { (res.thumbnail ?: res.bitmap).asImageBitmap() }
            Image(
                image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                colorFilter = (layer.content as? LayerContent.Image)?.adjustments?.toColorFilter(), filterQuality = FilterQuality.Medium,
            )
        } else {
            Icon(layer.type.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
