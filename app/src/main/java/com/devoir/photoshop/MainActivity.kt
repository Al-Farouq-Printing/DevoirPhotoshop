@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

package com.devoir.photoshop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devoir.photoshop.core.adaptive.LocalWindowSize
import com.devoir.photoshop.core.theme.DevoirTheme
import com.devoir.photoshop.navigation.AppNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appVm: AppViewModel = viewModel()
            val settings by appVm.settings.collectAsState()
            val windowSize = calculateWindowSizeClass(this)
            DevoirTheme(settings.themeMode) {
                CompositionLocalProvider(LocalWindowSize provides windowSize) {
                    AppNavHost(appVm)
                }
            }
        }
    }
}
