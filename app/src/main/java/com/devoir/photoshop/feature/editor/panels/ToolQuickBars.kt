package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.layers.ShapeType

@Composable
fun TextToolQuickBar(selectedTextId: String?, locked: Boolean, onNew: () -> Unit, onEdit: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onNew) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New text") }
        if (selectedTextId != null) {
            OutlinedButton(onClick = { onEdit(selectedTextId) }, enabled = !locked) { Text("Edit text") }
        }
        Text("Tap the canvas to add text, or tap text to edit it", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ShapeToolQuickBar(selected: ShapeType, onSelect: (ShapeType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapeType.entries.forEach { t -> FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(t.label) }) }
    }
}
