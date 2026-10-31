package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.crop.CropAspectOption
import com.devoir.photoshop.feature.editor.CropActions
import com.devoir.photoshop.feature.editor.CropUiState
import kotlin.math.roundToInt

/** Crop options + Apply/Cancel. Nothing changes in the document until Apply is pressed. */
@Composable
fun CropControls(crop: CropUiState, actions: CropActions, modifier: Modifier = Modifier) {
    var customDialog by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${crop.rect.width.roundToInt()} × ${crop.rect.height.roundToInt()} px",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CropAspectOption.entries.forEach { opt ->
                FilterChip(
                    selected = crop.aspect == opt,
                    onClick = { if (opt == CropAspectOption.Custom) customDialog = true else actions.onAspect(opt) },
                    label = { Text(opt.label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = actions.onApply, modifier = Modifier.weight(1f)) { Text("Apply") }
        }
    }
    if (customDialog) {
        var w by remember { mutableStateOf("1") }
        var h by remember { mutableStateOf("1") }
        val wv = ProjectMath.parseNumber(w)
        val hv = ProjectMath.parseNumber(h)
        val ok = wv != null && hv != null && wv > 0 && hv > 0
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text("Custom aspect ratio") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it }, Modifier.weight(1f), label = { Text("Width") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(h, { h = it }, Modifier.weight(1f), label = { Text("Height") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = { actions.onCustomRatio(wv!!.toFloat(), hv!!.toFloat()); customDialog = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text("Cancel") } },
        )
    }
}
