@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.devoir.photoshop.core.adaptive

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.staticCompositionLocalOf

/** Provided once in MainActivity; every screen reads it to pick its layout. */
val LocalWindowSize = staticCompositionLocalOf<WindowSizeClass> {
    error("WindowSizeClass not provided")
}

enum class EditorLayoutMode {
    /** Portrait phone: canvas + bottom tool strip, panels in a bottom sheet. */
    Compact,
    /** Landscape phone / foldable / small tablet: tool rail + canvas + one tabbed side panel. */
    Medium,
    /** Tablet: labelled tool rail + canvas + Properties and Layers visible together. */
    Expanded,
}

fun WindowSizeClass.editorLayoutMode(): EditorLayoutMode = when {
    widthSizeClass == WindowWidthSizeClass.Compact -> EditorLayoutMode.Compact
    // Landscape phones are wide but short: two stacked panels would be cramped.
    heightSizeClass == WindowHeightSizeClass.Compact -> EditorLayoutMode.Medium
    widthSizeClass == WindowWidthSizeClass.Medium -> EditorLayoutMode.Medium
    else -> EditorLayoutMode.Expanded
}
