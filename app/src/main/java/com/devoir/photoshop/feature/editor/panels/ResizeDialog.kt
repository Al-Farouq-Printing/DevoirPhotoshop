package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.core.project.ConfigResult
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.layers.Anchor
import com.devoir.photoshop.model.ProjectConfig
import com.devoir.photoshop.model.SizeUnit
import kotlin.math.roundToInt

enum class ResizeKind(val title: String) { Canvas("Canvas size"), Image("Image size") }

/**
 * Canvas size: changes the document bounds, layers keep their size, placed by the anchor.
 * Image size: changes the document resolution, layers scale with it. DPI is never changed here.
 */
@Composable
fun ResizeDialog(kind: ResizeKind, config: ProjectConfig, onDismiss: () -> Unit, onApply: (ProjectConfig, Anchor) -> Unit) {
    var unit by remember { mutableStateOf(config.unit) }
    fun show(px: Int, u: SizeUnit) = ProjectMath.formatValue(ProjectMath.fromPixels(px, u, config.dpi), u)
    var wText by remember { mutableStateOf(show(config.widthPx, config.unit)) }
    var hText by remember { mutableStateOf(show(config.heightPx, config.unit)) }
    var keepRatio by remember { mutableStateOf(kind == ResizeKind.Image) }
    var anchor by remember { mutableStateOf(Anchor.Center) }

    val hex = "%06X".format(config.backgroundColor and 0xFFFFFF)
    val result = ProjectMath.buildConfig(wText, hText, unit, config.dpi.toString(), config.backgroundType, hex)
    val errors = result as? ConfigResult.Invalid
    val valid = (result as? ConfigResult.Valid)?.config
    val changed = valid != null && (valid.widthPx != config.widthPx || valid.heightPx != config.heightPx)

    fun followRatio(fromWidth: Boolean, text: String) {
        val v = ProjectMath.parseNumber(text)
        if (!keepRatio || v == null || v <= 0.0) return
        if (fromWidth) {
            val px = ProjectMath.toPixels(v, unit, config.dpi)
            hText = show((px.toDouble() * config.heightPx / config.widthPx).roundToInt().coerceAtLeast(1), unit)
        } else {
            val px = ProjectMath.toPixels(v, unit, config.dpi)
            wText = show((px.toDouble() * config.widthPx / config.heightPx).roundToInt().coerceAtLeast(1), unit)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Current: ${config.widthPx} × ${config.heightPx} px · ${config.dpi} DPI",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        wText, { wText = it; followRatio(true, it) }, Modifier.weight(1f), label = { Text("Width") }, singleLine = true,
                        isError = errors?.widthError != null, supportingText = errors?.widthError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        hText, { hText = it; followRatio(false, it) }, Modifier.weight(1f), label = { Text("Height") }, singleLine = true,
                        isError = errors?.heightError != null, supportingText = errors?.heightError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SizeUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = unit == u,
                            onClick = {
                                valid?.let { wText = show(it.widthPx, u); hText = show(it.heightPx, u) }
                                unit = u
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, SizeUnit.entries.size),
                        ) { Text(u.suffix) }
                    }
                }
                if (kind == ResizeKind.Image) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keepRatio, { keepRatio = it })
                        Text("Maintain aspect ratio")
                    }
                    Text(
                        "Layers scale with the document and are drawn from their original pixels (nothing is resampled or flattened). Resolution stays ${config.dpi} DPI.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Anchor", style = MaterialTheme.typography.titleSmall)
                    AnchorGrid(anchor) { anchor = it }
                    Text(
                        "Layers keep their size. Content outside the new canvas is hidden, not deleted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (valid != null) {
                    Text("New: ${valid.widthPx} × ${valid.heightPx} px", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = { TextButton(enabled = changed, onClick = { valid?.let { onApply(it, anchor) } }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AnchorGrid(selected: Anchor, onSelect: (Anchor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Anchor.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { a ->
                    val on = a == selected
                    Box(
                        Modifier.size(44.dp)
                            .background(
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable { onSelect(a) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(10.dp).background(
                                if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(5.dp),
                            ),
                        )
                    }
                }
            }
        }
    }
}
