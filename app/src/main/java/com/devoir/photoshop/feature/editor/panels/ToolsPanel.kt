@file:OptIn(ExperimentalMaterial3Api::class)

package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.model.EditorTool

fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.Move -> Icons.Default.OpenWith
    EditorTool.Selection -> Icons.Default.HighlightAlt
    EditorTool.Crop -> Icons.Default.Crop
    EditorTool.Brush -> Icons.Default.Brush
    EditorTool.Eraser -> Icons.Default.CleaningServices
    EditorTool.Text -> Icons.Default.TextFields
    EditorTool.Shapes -> Icons.Default.Category
    EditorTool.Transform -> Icons.Default.Transform
    EditorTool.Color -> Icons.Default.Palette
    EditorTool.Filters -> Icons.Default.Filter
    EditorTool.Adjustments -> Icons.Default.AutoFixHigh
}

@Composable
fun ToolButton(tool: EditorTool, selected: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tool.icon(), contentDescription = tool.label)
            if (showLabel) Text(tool.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Vertical rail for landscape phones and tablets. */
@Composable
fun ToolRail(selected: EditorTool, onSelect: (EditorTool) -> Unit, showLabels: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.width(if (showLabels) 84.dp else 68.dp).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditorTool.entries.forEach { ToolButton(it, it == selected, showLabels) { onSelect(it) } }
    }
}

/** Horizontal strip for portrait phones. */
@Composable
fun ToolStrip(selected: EditorTool, onSelect: (EditorTool) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(EditorTool.entries) { ToolButton(it, it == selected, showLabel = true) { onSelect(it) } }
    }
}
