package com.devoir.photoshop

import androidx.lifecycle.ViewModel
import com.devoir.photoshop.core.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PerformanceMode(val label: String) { Balanced("Balanced"), Quality("Quality"), Saver("Battery saver") }

data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.System,
    val performance: PerformanceMode = PerformanceMode.Balanced,
    val showGrid: Boolean = false,
    val snapToGuides: Boolean = true,
    val autoSave: Boolean = true,
)

/** In-memory in Phase 1. Swap the backing store for DataStore later. */
class AppViewModel : ViewModel() {
    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()
    fun update(block: (SettingsState) -> SettingsState) = _settings.update(block)
}
