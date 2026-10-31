package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devoir.photoshop.engine.layers.FontCatalog
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.TextAlignment
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.feature.editor.LayerActions
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Create / edit text. Works on a draft; nothing touches the document until Add/Apply,
 * and Cancel leaves it exactly as it was. Editing an existing layer keeps the same layer.
 */
@Composable
fun TextEditorDialog(initial: TextSpec, isNew: Boolean, docWidth: Int, onDismiss: () -> Unit, onApply: (TextSpec) -> Unit) {
    var spec by remember { mutableStateOf(initial) }
    var showColor by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Text(if (isNew) "Add text" else "Edit text", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = spec.text, onValueChange = { spec = spec.copy(text = it.take(TextSpec.MAX_CHARS)) },
                        label = { Text("Text") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    )
                    TextPreview(spec)
                    FontDropdown(spec.fontId, true) { spec = spec.copy(fontId = it) }
                    FontSizeRow(spec.fontSize, true) { spec = spec.copy(fontSize = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(spec.bold, { spec = spec.copy(bold = !spec.bold) }, label = { Text("Bold") })
                        FilterChip(spec.italic, { spec = spec.copy(italic = !spec.italic) }, label = { Text("Italic") })
                        Spacer(Modifier.weight(1f))
                        AlphaSwatch(spec.color, 36.dp) { showColor = true }
                        Text("Color", style = MaterialTheme.typography.labelMedium)
                    }
                    AlignmentRow(spec.alignment, true) { spec = spec.copy(alignment = it) }
                    LabeledSlider("Letter spacing", String.format(Locale.US, "%.2f em", spec.letterSpacing), spec.letterSpacing, -0.05f..0.5f, true) {
                        spec = spec.copy(letterSpacing = it)
                    }
                    LabeledSlider("Line spacing", String.format(Locale.US, "×%.2f", spec.lineSpacing), spec.lineSpacing, 0.6f..3f, true) {
                        spec = spec.copy(lineSpacing = it)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = spec.boxWidth != null,
                            onCheckedChange = { on -> spec = spec.copy(boxWidth = if (on) docWidth * 0.6f else null) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Wrap text in a box", style = MaterialTheme.typography.labelMedium)
                    }
                    spec.boxWidth?.let { bw ->
                        LabeledSlider("Box width", "${bw.roundToInt()} px", bw, 40f..docWidth.toFloat().coerceAtLeast(41f), true) {
                            spec = spec.copy(boxWidth = it)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onApply(spec) }, enabled = spec.text.isNotBlank()) { Text(if (isNew) "Add" else "Apply") }
                }
            }
        }
    }
    if (showColor) {
        ColorPickerDialog(
            initial = spec.color, withAlpha = true,
            onDismiss = { showColor = false },
            onConfirm = { spec = spec.copy(color = it); showColor = false },
        )
    }
}

@Composable
private fun TextPreview(spec: TextSpec) {
    val checker = rememberCheckerBrush(8.dp)
    val shown = remember(spec) { (if (spec.text.isBlank()) spec.copy(text = "Preview") else spec).sanitized() }
    Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(checker)) {
        TextThumb(shown, Modifier.fillMaxSize().padding(8.dp))
    }
}

/** Quick edits for the selected text layer; each change updates the same layer immediately. */
@Composable
fun TextLayerControls(layer: Layer, spec: TextSpec, actions: LayerActions) {
    val enabled = !layer.locked
    var showColor by remember { mutableStateOf(false) }
    fun set(n: TextSpec) = actions.onUpdateText(layer.id, n)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layer.locked) {
            Text("Layer is locked — unlock it to edit the text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { actions.onEditText(layer.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit text…")
        }
        FontDropdown(spec.fontId, enabled) { set(spec.copy(fontId = it)) }
        FontSizeRow(spec.fontSize, enabled) { set(spec.copy(fontSize = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(spec.bold, { set(spec.copy(bold = !spec.bold)) }, label = { Text("Bold") }, enabled = enabled)
            FilterChip(spec.italic, { set(spec.copy(italic = !spec.italic)) }, label = { Text("Italic") }, enabled = enabled)
            Spacer(Modifier.weight(1f))
            AlphaSwatch(spec.color, 36.dp, enabled) { showColor = true }
        }
        AlignmentRow(spec.alignment, enabled) { set(spec.copy(alignment = it)) }
    }
    if (showColor) {
        ColorPickerDialog(
            initial = spec.color, withAlpha = true,
            onDismiss = { showColor = false },
            onConfirm = { set(spec.copy(color = it)); showColor = false },
        )
    }
}

@Composable
fun FontDropdown(fontId: String, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Font: ${FontCatalog.byId(fontId).label}", Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FontCatalog.all.forEach { f ->
                DropdownMenuItem(text = { Text(f.label) }, onClick = { open = false; onChange(f.id) })
            }
        }
    }
}

@Composable
fun FontSizeRow(size: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    LabeledSlider("Font size", "${size.roundToInt()} px", TextSpec.sizeToSlider(size), 0f..1f, enabled) {
        onChange(TextSpec.sliderToSize(it).roundToInt().toFloat())
    }
}

private fun TextAlignment.icon(): ImageVector = when (this) {
    TextAlignment.Left -> Icons.Default.FormatAlignLeft
    TextAlignment.Center -> Icons.Default.FormatAlignCenter
    TextAlignment.Right -> Icons.Default.FormatAlignRight
    TextAlignment.Justify -> Icons.Default.FormatAlignJustify
}

@Composable
fun AlignmentRow(selected: TextAlignment, enabled: Boolean, onChange: (TextAlignment) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TextAlignment.entries.forEachIndexed { i, a ->
            SegmentedButton(
                selected = a == selected, onClick = { onChange(a) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, TextAlignment.entries.size),
                icon = {},
            ) { Icon(a.icon(), a.name) }
        }
    }
}
