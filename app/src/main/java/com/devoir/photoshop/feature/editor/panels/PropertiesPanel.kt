package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.feature.editor.EditorUiState
import com.devoir.photoshop.feature.editor.LayerActions
import com.devoir.photoshop.feature.editor.ToolCallbacks
import com.devoir.photoshop.model.EditorTool
import kotlin.math.roundToInt

@Composable
fun PropertiesPanel(
    tool: EditorTool,
    document: CanvasDocument,
    actions: LayerActions,
    ui: EditorUiState,
    tools: ToolCallbacks,
    modifier: Modifier = Modifier,
) {
    val layer = document.selectedLayer
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${tool.label} options", style = MaterialTheme.typography.titleSmall)
        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (tool) {
            EditorTool.Move -> Text(
                "Drag with one finger to move the selected layer. Use two fingers to zoom and pan the canvas.",
                style = MaterialTheme.typography.bodySmall,
            )
            EditorTool.Transform -> Text(
                "Drag the layer to move it, the corner squares to scale, and the round handle to rotate. " +
                    "Touch outside the box to pan; use two fingers to zoom.",
                style = MaterialTheme.typography.bodySmall,
            )
            EditorTool.Crop -> {
                Text(
                    "Drag the edges or corners, or the inside to move the frame. Nothing changes until you press Apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.crop != null) CropControls(ui.crop, tools.crop)
            }
            EditorTool.Text -> {
                Text(
                    "Tap the canvas to add text there, or tap existing text to edit it. Dragging pans; two fingers zoom.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = tools.onNewText) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New text")
                }
            }
            EditorTool.Shapes -> {
                Text(
                    "Choose a shape, then drag on the canvas to draw it (a tap adds a default-size one). A second finger cancels.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShapeType.entries.forEach { t ->
                        FilterChip(selected = t == ui.shapeType, onClick = { tools.onShapeType(t) }, label = { Text(t.label) })
                    }
                }
            }
            EditorTool.Brush -> {
                Text(
                    "One finger paints on the selected paint layer (a new one is created if an image is selected). Two fingers zoom and pan.",
                    style = MaterialTheme.typography.bodySmall,
                )
                BrushControls(BrushMode.Paint, ui.brush) { tools.onBrushChange(BrushMode.Paint, it) }
            }
            EditorTool.Eraser -> {
                Text(
                    "One finger erases pixels from the selected paint layer. Two fingers zoom and pan.",
                    style = MaterialTheme.typography.bodySmall,
                )
                BrushControls(BrushMode.Erase, ui.eraser) { tools.onBrushChange(BrushMode.Erase, it) }
            }
            EditorTool.Filters -> {
                Text(
                    "Pick a filter for the selected image layer. It is applied live and non-destructively; the original pixels are kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterStrip(layer, actions)
            }
            EditorTool.Adjustments -> {
                Text(
                    "Adjust the selected image layer. Changes are live and non-destructive. Tap a value to reset that slider.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AdjustmentSliders(layer, actions)
            }
            else -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("Tool settings will appear here once this tool is implemented.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Layer properties", style = MaterialTheme.typography.titleSmall)
        if (layer == null) {
            Text(
                "Background · ${document.width} × ${document.height} px · ${document.config.dpi} DPI.\nSelect a layer to edit it.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(layer.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${layer.type.label} layer · ${(layer.width * layer.transform.scaleX).roundToInt()} × ${(layer.height * layer.transform.scaleY).roundToInt()} px",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (layer.type == LayerType.Paint) {
                val bmp = layer.content.resource?.bitmap
                Text(
                    if (bmp == null) "Empty — pixels are allocated on the first stroke" else "Pixel buffer ${bmp.width} × ${bmp.height}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val c = layer.content) {
                is LayerContent.Text -> TextLayerControls(layer, c.spec, actions)
                is LayerContent.Shape -> ShapeLayerControls(layer, c.spec, actions)
                else -> {}
            }
            if (layer.content is LayerContent.Text || layer.content is LayerContent.Shape) HorizontalDivider()
            Text("Transform", style = MaterialTheme.typography.titleSmall)
            TransformControls(layer, document, actions, ui.lockRatio, tools.onLockRatio)
        }
    }
}
