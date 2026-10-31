package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.transform.TransformMath
import com.devoir.photoshop.feature.editor.LayerActions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Numeric transform fields. Every valid entry is applied immediately; invalid entries are flagged and ignored. */
@Composable
fun TransformControls(
    layer: Layer, doc: CanvasDocument, actions: LayerActions,
    lockRatio: Boolean, onLockRatio: (Boolean) -> Unit,
) {
    val t = layer.transform
    val enabled = !layer.locked
    val w = layer.width * t.scaleX
    val h = layer.height * t.scaleY
    fun set(n: LayerTransform) = actions.onTransform(layer.id, n)
    val wRange = layer.width * TransformMath.MIN_SCALE..layer.width * TransformMath.MAX_SCALE
    val hRange = layer.height * TransformMath.MIN_SCALE..layer.height * TransformMath.MAX_SCALE
    val pct = TransformMath.MIN_SCALE * 100f..TransformMath.MAX_SCALE * 100f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layer.locked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("Layer is locked — unlock it to transform.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("X", t.x, 0f..doc.width.toFloat(), enabled, Modifier.weight(1f)) { set(t.copy(x = it)) }
            NumberField("Y", t.y, 0f..doc.height.toFloat(), enabled, Modifier.weight(1f)) { set(t.copy(y = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Width", w, wRange, enabled, Modifier.weight(1f)) { nw ->
                val sx = nw / layer.width
                set(if (lockRatio) t.copy(scaleX = sx, scaleY = t.scaleY * (sx / t.scaleX)) else t.copy(scaleX = sx))
            }
            NumberField("Height", h, hRange, enabled, Modifier.weight(1f)) { nh ->
                val sy = nh / layer.height
                set(if (lockRatio) t.copy(scaleY = sy, scaleX = t.scaleX * (sy / t.scaleY)) else t.copy(scaleY = sy))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Scale X %", t.scaleX * 100f, pct, enabled, Modifier.weight(1f)) { p ->
                val sx = p / 100f
                set(if (lockRatio) t.copy(scaleX = sx, scaleY = t.scaleY * (sx / t.scaleX)) else t.copy(scaleX = sx))
            }
            NumberField("Scale Y %", t.scaleY * 100f, pct, enabled, Modifier.weight(1f)) { p ->
                val sy = p / 100f
                set(if (lockRatio) t.copy(scaleY = sy, scaleX = t.scaleX * (sy / t.scaleY)) else t.copy(scaleY = sy))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("Rotation °", t.rotation, -3600f..3600f, enabled, Modifier.weight(1f)) { set(t.copy(rotation = it)) }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = lockRatio, onCheckedChange = onLockRatio)
                Spacer(Modifier.width(8.dp))
                Text("Lock ratio", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onFlip(layer.id, true) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SwapHoriz, null); Spacer(Modifier.width(4.dp)); Text("Flip H")
            }
            OutlinedButton(onClick = { actions.onFlip(layer.id, false) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.SwapVert, null); Spacer(Modifier.width(4.dp)); Text("Flip V")
            }
        }
        OutlinedButton(onClick = { actions.onResetTransform(layer.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(4.dp)); Text("Reset transform")
        }
    }
}

/** Compact-phone strip for the Transform tool. */
@Composable
fun TransformQuickBar(layer: Layer?, lockRatio: Boolean, onLockRatio: (Boolean) -> Unit, actions: LayerActions) {
    val editable = layer != null && !layer.locked
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = lockRatio, onClick = { onLockRatio(!lockRatio) }, label = { Text("Lock ratio") })
        AssistChip(onClick = { layer?.let { actions.onFlip(it.id, true) } }, enabled = editable, label = { Text("Flip H") })
        AssistChip(onClick = { layer?.let { actions.onFlip(it.id, false) } }, enabled = editable, label = { Text("Flip V") })
        AssistChip(onClick = { layer?.let { actions.onResetTransform(it.id) } }, enabled = editable, label = { Text("Reset") })
        Text(
            when {
                layer == null -> "Select a layer"
                layer.locked -> "Locked"
                else -> "${layer.transform.rotation.roundToInt()}°"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun NumberField(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    modifier: Modifier, onCommit: (Float) -> Unit,
) {
    fun fmt(v: Float) = if (abs(v - v.roundToInt()) < 0.005f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
    var text by remember { mutableStateOf(fmt(value)) }
    var focused by remember { mutableStateOf(false) }
    // Follow the model (e.g. while dragging on the canvas) unless the user is typing here.
    LaunchedEffect(value, focused) { if (!focused) text = fmt(value) }
    val parsed = ProjectMath.parseNumber(text)?.toFloat()
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val v = ProjectMath.parseNumber(it)?.toFloat()
            if (v != null && v in range) onCommit(v)
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        enabled = enabled,
        isError = parsed == null || parsed !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
