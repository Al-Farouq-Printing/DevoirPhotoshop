package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import kotlin.math.roundToInt

private val Presets = listOf(
    0x000000, 0xFFFFFF, 0x808080, 0xF44336, 0xFF9800, 0xFFEB3B, 0x4CAF50, 0x2196F3, 0x9C27B0, 0x795548,
)

private fun hexOf(r: Int, g: Int, b: Int) = "%02X%02X%02X".format(r, g, b)

/** Local RGB picker (sliders, hex, presets). With [withAlpha] it also edits transparency and returns ARGB. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit, withAlpha: Boolean = false) {
    var a by remember { mutableStateOf(if (withAlpha) (initial ushr 24) and 0xFF else 255) }
    var r by remember { mutableStateOf((initial shr 16) and 0xFF) }
    var g by remember { mutableStateOf((initial shr 8) and 0xFF) }
    var b by remember { mutableStateOf(initial and 0xFF) }
    var hex by remember { mutableStateOf(hexOf(r, g, b)) }
    val checker = rememberCheckerBrush(8.dp)

    fun setRgb(nr: Int, ng: Int, nb: Int) { r = nr; g = ng; b = nb; hex = hexOf(nr, ng, nb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (withAlpha) "Color & transparency" else "Color") },
        text = {
            Column(Modifier.verticalScrollSafe(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(checker)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                ) { Box(Modifier.fillMaxSize().background(Color(r, g, b, a))) }
                Channel("R", r, 255) { setRgb(it, g, b) }
                Channel("G", g, 255) { setRgb(r, it, b) }
                Channel("B", b, 255) { setRgb(r, g, it) }
                if (withAlpha) Channel("A", a, 255) { a = it }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        val clean = v.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                        hex = clean
                        ProjectMath.parseHexColor(clean)?.let { c -> r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF }
                    },
                    label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true,
                    isError = ProjectMath.parseHexColor(hex) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.forEach { rgb ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(Color(0xFF000000 or rgb.toLong()))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { setRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm((a shl 24) or (r shl 16) or (g shl 8) or b) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier =
    this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun Channel(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(18.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(), modifier = Modifier.weight(1f),
        )
        Text("$value", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium)
    }
}
