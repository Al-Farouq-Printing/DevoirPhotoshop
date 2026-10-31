package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.feature.editor.LayerActions
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import kotlin.math.min
import kotlin.math.roundToInt

/** Properties of the selected shape layer. Every change is applied to the layer immediately. */
@Composable
fun ShapeLayerControls(layer: Layer, spec: ShapeSpec, actions: LayerActions) {
    val enabled = !layer.locked
    fun set(s: ShapeSpec) = actions.onUpdateShape(layer.id, s)
    val isLine = spec.type == ShapeType.Line
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layer.locked) {
            Text("Layer is locked — unlock it to edit the shape.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!isLine) ColorOpacityRow("Fill", spec.fillColor, enabled) { set(spec.copy(fillColor = it)) }
        ColorOpacityRow(if (isLine) "Color" else "Stroke", spec.strokeColor, enabled) { set(spec.copy(strokeColor = it)) }
        LabeledSlider(
            if (isLine) "Width" else "Stroke width", "${spec.strokeWidth.roundToInt()} px", spec.strokeWidth,
            (if (isLine) 1f else 0f)..ShapeSpec.MAX_STROKE, enabled,
        ) { set(spec.copy(strokeWidth = it.roundToInt().toFloat())) }
        if (spec.type == ShapeType.Rectangle || spec.type == ShapeType.RoundedRectangle) {
            val t = layer.transform
            val maxR = (min(layer.width * t.scaleX, layer.height * t.scaleY) / 2f).coerceAtLeast(1f)
            LabeledSlider("Corner radius", "${spec.cornerRadius.coerceAtMost(maxR).roundToInt()} px", spec.cornerRadius.coerceAtMost(maxR), 0f..maxR, enabled) {
                set(spec.copy(cornerRadius = it.roundToInt().toFloat()))
            }
        }
        if (spec.type == ShapeType.Polygon || spec.type == ShapeType.Star) {
            LabeledSlider(if (spec.type == ShapeType.Star) "Points" else "Sides", "${spec.sides}", spec.sides.toFloat(), 3f..12f, enabled) {
                set(spec.copy(sides = it.roundToInt()))
            }
        }
        if (isLine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = spec.roundCaps, onCheckedChange = { set(spec.copy(roundCaps = it)) }, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text("Rounded caps", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ColorOpacityRow(label: String, argb: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val alpha = (argb ushr 24) / 255f
    Row(verticalAlignment = Alignment.CenterVertically) {
        AlphaSwatch(argb, 36.dp, enabled) { showPicker = true }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("$label opacity", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = alpha, enabled = enabled,
                onValueChange = { a -> onChange(((a * 255f).roundToInt() shl 24) or (argb and 0xFFFFFF)) },
            )
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = argb or 0xFF000000.toInt(),
            onDismiss = { showPicker = false },
            onConfirm = { rgb -> onChange((argb and 0xFF000000.toInt()) or (rgb and 0xFFFFFF)); showPicker = false },
        )
    }
}

@Composable
fun LabeledSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

/** A colour dot that shows transparency on a checkerboard. */
@Composable
fun AlphaSwatch(argb: Int, size: Dp, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val checker = rememberCheckerBrush(5.dp)
    var m = Modifier.size(size).clip(CircleShape).background(checker).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    if (onClick != null && enabled) m = m.clickable(onClick = onClick)
    Box(m) { Box(Modifier.fillMaxSize().background(Color(argb))) }
}
