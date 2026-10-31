@file:OptIn(ExperimentalMaterial3Api::class)

package com.devoir.photoshop.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.AppViewModel
import com.devoir.photoshop.PerformanceMode
import com.devoir.photoshop.core.theme.ThemeMode

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("Appearance") {
                    Text("Theme", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.themeMode == mode,
                                onClick = { vm.update { it.copy(themeMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                    InfoItem("Language", "English · more languages in a later phase", Icons.Default.Language)
                }
                Section("Performance") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                        PerformanceMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.performance == mode,
                                onClick = { vm.update { it.copy(performance = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, PerformanceMode.entries.size),
                            ) { Text(mode.label, maxLines = 1) }
                        }
                    }
                    Text(
                        "Saved for the editing engine; no effect in Phase 1.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Section("Storage") {
                    InfoItem("Project storage", "On this device only · no cloud", Icons.Default.Storage)
                    InfoItem("Cache", "Management arrives with project saving", Icons.Default.CleaningServices)
                }
                Section("Editor") {
                    Toggle("Show grid", Icons.Default.GridOn, s.showGrid) { v -> vm.update { it.copy(showGrid = v) } }
                    Toggle("Snap to guides", Icons.Default.Straighten, s.snapToGuides) { v -> vm.update { it.copy(snapToGuides = v) } }
                    Toggle("Auto-save", Icons.Default.Save, s.autoSave) { v -> vm.update { it.copy(autoSave = v) } }
                }
                Section("About DevoirPhotoshop") {
                    InfoItem("DevoirPhotoshop", "Version 0.1.0 · Phase 1 (structure only)", Icons.Default.Info)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 8.dp, top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Card { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun InfoItem(title: String, subtitle: String, icon: ImageVector) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Toggle(title: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
