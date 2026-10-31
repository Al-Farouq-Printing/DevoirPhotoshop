package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.crop.CropRect
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.paint.ActiveStroke
import com.devoir.photoshop.feature.editor.canvas.CanvasInput
import com.devoir.photoshop.feature.editor.canvas.CanvasView
import com.devoir.photoshop.feature.editor.canvas.ViewportController
import com.devoir.photoshop.feature.editor.canvas.ViewportTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CanvasWorkspace(
    document: CanvasDocument,
    viewport: ViewportController,
    isImporting: Boolean,
    input: CanvasInput,
    activeStroke: ActiveStroke?,
    strokeTick: State<Long>,
    adjustTick: State<Long>,
    processedLookup: (String) -> Bitmap?,
    crop: CropRect?,
    transformInfo: String?,
    lockedHint: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    fun animate(block: () -> ViewportTarget) {
        job?.cancel()
        job = scope.launch { viewport.animateTo(block()) }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        CanvasView(document, viewport, input, activeStroke, strokeTick, adjustTick, processedLookup, crop, Modifier.fillMaxSize())

        Surface(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ) {
            Text(
                "${document.width} × ${document.height} px · ${document.config.dpi} DPI",
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (transformInfo != null || lockedHint) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (lockedHint) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (lockedHint) {
                        Icon(Icons.Default.Lock, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Layer is locked", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text(transformInfo ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (document.layers.isEmpty()) {
            FilledTonalButton(
                onClick = onImport, enabled = !isImporting,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(6.dp))
                Text("Import image")
            }
        }

        Surface(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 2.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { animate { viewport.stepTarget(1 / 1.25f) } }) { Icon(Icons.Default.ZoomOut, "Zoom out") }
                TextButton(onClick = { animate { viewport.fitTarget() } }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("${viewport.zoomPercent}%", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { animate { viewport.stepTarget(1.25f) } }) { Icon(Icons.Default.ZoomIn, "Zoom in") }
            }
        }

        if (isImporting) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}
