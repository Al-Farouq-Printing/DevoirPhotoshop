@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)

package com.devoir.photoshop.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.core.adaptive.LocalWindowSize
import com.devoir.photoshop.model.Project
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    projects: List<Project>,
    onNewProject: () -> Unit,
    onOpenProject: (Project) -> Unit,
    onSettings: () -> Unit,
    onImportImage: suspend (Uri) -> String?,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { onImportImage(uri)?.let { snack.showSnackbar(it) } }
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    fun comingLater(feature: String) {
        scope.launch { snack.showSnackbar("$feature arrives in a later phase") }
    }
    val wide = LocalWindowSize.current.widthSizeClass != WindowWidthSizeClass.Compact

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("DevoirPhotoshop") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        if (wide) {
            Row(
                Modifier.padding(pad).padding(24.dp).fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                    SectionTitle("Start")
                    Spacer(Modifier.height(12.dp))
                    ActionButtons(onNewProject, { comingLater("Opening projects") }, { pickImage() })
                }
                RecentGrid(projects, onOpenProject, Modifier.weight(1f)) { SectionTitle("Recent projects") }
            }
        } else {
            RecentGrid(projects, onOpenProject, Modifier.padding(pad)) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    ActionButtons(onNewProject, { comingLater("Opening projects") }, { pickImage() })
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("Recent projects")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun ActionButtons(onNew: () -> Unit, onOpen: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Create New Project")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Open")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Import")
            }
        }
    }
}

@Composable
private fun RecentGrid(
    projects: List<Project>,
    onOpen: (Project) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(projects, key = { it.id }) { ProjectCard(it) { onOpen(it) } }
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                .background(Color.hsv((project.colorSeed * 55f) % 360f, 0.45f, 0.55f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Image, null, tint = Color.White.copy(alpha = 0.7f)) }
        Column(Modifier.padding(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                project.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
