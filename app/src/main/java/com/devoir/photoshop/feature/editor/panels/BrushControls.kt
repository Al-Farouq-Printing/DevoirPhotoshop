package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.engine.paint.BrushSettings
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import kotlin.math.min
import kotlin.math.roundToInt

/** Full brush/eraser options (used in the Properties panel and the compact sheet). */
@Composable
fun BrushControls(mode: BrushMode, settings: BrushSettings, onChange: (BrushSettings) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BrushPreview(mode, settings)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Size", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("${settings.size.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(settings.copy(size = (settings.size - 1f)).sanitized()) }) { Icon(Icons.Default.Remove, "Decrease size") }
            Slider(
                value = BrushSettings.sizeToSlider(settings.size),
                onValueChange = { onChange(settings.copy(size = BrushSettings.sliderToSize(it).roundToInt().toFloat())) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(settings.copy(size = (settings.size + 1f)).sanitized()) }) { Icon(Icons.Default.Add, "Increase size") }
        }

        PercentSlider("Opacity", settings.opacity) { onChange(settings.copy(opacity = it)) }
        PercentSlider("Hardness", settings.hardness) { onChange(settings.copy(hardness = it)) }

        if (mode == BrushMode.Paint) {
            Row(Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(settings.color, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Color", style = MaterialTheme.typography.labelMedium)
                    Text("#%06X".format(settings.color and 0xFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showPicker = true }) { Text("Change") }
            }
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = settings.color,
            onDismiss = { showPicker = false },
            onConfirm = { onChange(settings.copy(color = it)); showPicker = false },
        )
    }
}

/** Compact-phone strip shown above the tool strip: the controls used most while painting. */
@Composable
fun BrushQuickBar(mode: BrushMode, settings: BrushSettings, onChange: (BrushSettings) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mode == BrushMode.Paint) {
            Box(Modifier.clickable { showPicker = true }.padding(end = 12.dp)) { ColorSwatch(settings.color, 32.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text("Size ${settings.size.roundToInt()} px", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = BrushSettings.sizeToSlider(settings.size),
                onValueChange = { onChange(settings.copy(size = BrushSettings.sliderToSize(it).roundToInt().toFloat())) },
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Opacity ${(settings.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(value = settings.opacity, onValueChange = { onChange(settings.copy(opacity = it)) })
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = settings.color,
            onDismiss = { showPicker = false },
            onConfirm = { onChange(settings.copy(color = it)); showPicker = false },
        )
    }
}

@Composable
private fun PercentSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange)
    }
}

@Composable
private fun ColorSwatch(argb: Int, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** Shows the dab shape (size is clamped to the preview area). */
@Composable
private fun BrushPreview(mode: BrushMode, settings: BrushSettings) {
    val checker = rememberCheckerBrush(6.dp)
    Box(
        Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)).background(checker),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = min(size.width, size.height) / 2f - 4.dp.toPx()
            val r = min(settings.size / 2f * density.coerceAtLeast(1f) / 2f, maxR).coerceAtLeast(1.5f)
            val base = if (mode == BrushMode.Paint) Color(settings.color) else Color(0xFF444444)
            val c = base.copy(alpha = settings.opacity)
            val center = Offset(size.width / 2f, size.height / 2f)
            if (settings.hardness >= 0.99f) {
                drawCircle(c, r, center)
            } else {
                drawCircle(
                    Brush.radialGradient(
                        0f to c, settings.hardness to c, 1f to c.copy(alpha = 0f),
                        center = center, radius = r,
                    ),
                    r, center,
                )
            }
        }
    }
}
