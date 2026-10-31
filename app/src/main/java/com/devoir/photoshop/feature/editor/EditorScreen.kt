@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)

package com.devoir.photoshop.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devoir.photoshop.core.adaptive.EditorLayoutMode
import com.devoir.photoshop.core.adaptive.LocalWindowSize
import com.devoir.photoshop.core.adaptive.editorLayoutMode
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.feature.editor.canvas.CanvasInput
import com.devoir.photoshop.feature.editor.canvas.InputMode
import com.devoir.photoshop.feature.editor.panels.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.devoir.photoshop.model.EditorTool
import kotlin.math.roundToInt

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: EditorViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val document by vm.document.collectAsState()
    val activeStroke by vm.activeStroke.collectAsState()
    val strokeTick = vm.strokeTick.collectAsState() // passed as State: only the canvas draw phase reads it
    val adjustTick = vm.adjustTick.collectAsState()
    val historyState by vm.historyState.collectAsState()
    val selectedLayer = document.selectedLayer
    val tool = state.selectedTool
    val mode = LocalWindowSize.current.editorLayoutMode()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var resizeKind by remember { mutableStateOf<ResizeKind?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    // Physical keyboard: Ctrl+Z undo, Ctrl+Y or Ctrl+Shift+Z redo (Cmd on macOS keyboards).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val keyHandler: (KeyEvent) -> Boolean = { e ->
        if (e.type == KeyEventType.KeyDown && (e.isCtrlPressed || e.isMetaPressed)) {
            when {
                e.key == Key.Z && e.isShiftPressed -> { vm.redo(); true }
                e.key == Key.Z -> { vm.undo(); true }
                e.key == Key.Y -> { vm.redo(); true }
                else -> false
            }
        } else false
    }
    val snack = remember { SnackbarHostState() }

    val paintMode = when (tool) {
        EditorTool.Brush -> BrushMode.Paint
        EditorTool.Eraser -> BrushMode.Erase
        else -> null
    }
    val paintSettings = if (paintMode == BrushMode.Erase) state.eraser else state.brush
    val transformable = selectedLayer != null && selectedLayer.visible && !selectedLayer.locked
    val inputMode = when {
        paintMode != null -> InputMode.Draw
        tool == EditorTool.Transform -> InputMode.Transform
        tool == EditorTool.Crop -> InputMode.Crop
        tool == EditorTool.Text -> InputMode.Text
        tool == EditorTool.Shapes -> InputMode.Shape
        tool == EditorTool.Move && transformable -> InputMode.MoveLayer
        else -> InputMode.Navigate
    }
    val showHandles = tool == EditorTool.Transform && transformable
    val lockedHint = selectedLayer?.locked == true &&
        (tool == EditorTool.Move || tool == EditorTool.Transform || paintMode != null)
    val transformInfo = if (tool == EditorTool.Transform && selectedLayer != null && !selectedLayer.locked) {
        val t = selectedLayer.transform
        "${(selectedLayer.width * t.scaleX).roundToInt()} × ${(selectedLayer.height * t.scaleY).roundToInt()} px · ${t.rotation.roundToInt()}°"
    } else null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importImage(uri)
    }
    val launchImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val actions = remember(vm, picker) {
        LayerActions(
            onSelect = vm::selectLayer, onSetVisible = vm::setLayerVisible, onSetLocked = vm::setLayerLocked,
            onSetOpacity = vm::setLayerOpacity, onAddPaint = vm::addPaintLayer,
            onAddImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDuplicate = vm::duplicateLayer, onDelete = vm::deleteLayer, onRename = vm::renameLayer,
            onMove = vm::moveLayer, onTransform = vm::setLayerTransform,
            onFlip = vm::flipLayer, onResetTransform = vm::resetLayerTransform,
            onAddText = vm::newText, onAddShape = vm::addShapeAtCenter, onEditText = vm::openEditText,
            onUpdateText = vm::updateTextSpec, onUpdateShape = vm::updateShapeSpec,
            onSetAdjustments = vm::setLayerAdjustments, onResetAdjustValues = vm::resetAdjustmentValues,
            onResetFilter = vm::resetFilter, onResetAllAdjust = vm::resetAllAdjustments, onRevertAdjust = vm::revertAdjustments,
        )
    }
    val cropActions = remember(vm) {
        CropActions(vm::setCropAspect, vm::setCustomCropRatio, vm::applyCrop, vm::cancelCrop)
    }

    val toolCallbacks = remember(vm, cropActions) {
        ToolCallbacks(vm::updateBrush, vm::setLockRatio, vm::setShapeType, vm::newText, cropActions)
    }
    val selectedTextId = selectedLayer?.takeIf { it.content is LayerContent.Text }?.id

    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
    }

    val canvasInput = remember(inputMode, paintSettings.size, showHandles, vm) {
        CanvasInput(
            mode = inputMode, brushDiameterDoc = paintSettings.size,
            onLayerDrag = vm::moveSelectedBy,
            onStrokeStart = vm::beginStroke, onStrokeMove = vm::moveStroke,
            onStrokeEnd = vm::endStroke, onStrokeCancel = vm::cancelStroke,
            showTransformHandles = showHandles,
            onTransformStart = vm::beginTransform, onTransformMove = vm::moveTransform, onTransformEnd = vm::endTransform,
            onCropStart = vm::beginCrop, onCropMove = vm::moveCrop, onCropEnd = vm::endCrop,
            onTextTap = vm::tapText,
            onShapeStart = vm::beginShape, onShapeMove = vm::moveShape, onShapeEnd = vm::endShape, onShapeCancel = vm::cancelShape,
        )
    }

    val canvas: @Composable (Modifier) -> Unit = { m ->
        CanvasWorkspace(
            document, vm.viewport, state.isImporting, canvasInput, activeStroke, strokeTick,
            adjustTick = adjustTick, processedLookup = vm::processedBitmap,
            crop = state.crop?.rect, transformInfo = transformInfo, lockedHint = lockedHint,
            onImport = { launchImport() }, modifier = m,
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().focusRequester(focusRequester).focusable().onPreviewKeyEvent(keyHandler)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                EditorTopBar(
                    title = vm.projectName,
                    subtitle = "${document.width} × ${document.height} px · ${document.config.dpi} DPI",
                    compact = mode == EditorLayoutMode.Compact,
                    importEnabled = !state.isImporting,
                    onBack = onBack, onImport = { launchImport() }, onSettings = onOpenSettings,
                    onCanvasSize = { resizeKind = ResizeKind.Canvas },
                    onImageSize = { resizeKind = ResizeKind.Image },
                    canUndo = historyState.canUndo, canRedo = historyState.canRedo,
                    undoLabel = historyState.undoLabel, redoLabel = historyState.redoLabel,
                    onUndo = vm::undo, onRedo = vm::redo, onHistory = { showHistory = true },
                )
                when (mode) {
                    EditorLayoutMode.Compact -> {
                        canvas(Modifier.weight(1f).fillMaxWidth())
                        when {
                            paintMode != null -> {
                                HorizontalDivider()
                                BrushQuickBar(paintMode, paintSettings) { vm.updateBrush(paintMode, it) }
                            }
                            tool == EditorTool.Transform -> {
                                HorizontalDivider()
                                TransformQuickBar(selectedLayer, state.lockRatio, vm::setLockRatio, actions)
                            }
                            tool == EditorTool.Filters -> {
                                HorizontalDivider()
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { FilterStrip(selectedLayer, actions) }
                            }
                            tool == EditorTool.Adjustments -> {
                                HorizontalDivider()
                                AdjustQuickBar(selectedLayer, { vm.selectPanel(PanelTab.Properties); showSheet = true }, actions)
                            }
                            tool == EditorTool.Text -> {
                                HorizontalDivider()
                                TextToolQuickBar(selectedTextId, selectedLayer?.locked == true, vm::newText, vm::openEditText)
                            }
                            tool == EditorTool.Shapes -> {
                                HorizontalDivider()
                                ShapeToolQuickBar(state.shapeType, vm::setShapeType)
                            }
                            tool == EditorTool.Crop && state.crop != null -> {
                                HorizontalDivider()
                                CropControls(state.crop!!, cropActions, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ToolStrip(tool, vm::selectTool, Modifier.weight(1f))
                            IconButton(onClick = { vm.selectPanel(PanelTab.Layers); showSheet = true }) {
                                Icon(Icons.Default.Layers, "Open layers panel")
                            }
                            IconButton(onClick = { vm.selectPanel(PanelTab.Properties); showSheet = true }) {
                                Icon(Icons.Default.Tune, "Open properties panel")
                            }
                        }
                    }
                    else -> Row(Modifier.weight(1f).fillMaxWidth()) {
                        ToolRail(tool, vm::selectTool, showLabels = mode == EditorLayoutMode.Expanded)
                        VerticalDivider()
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider()
                        val props: @Composable (Modifier) -> Unit = { m ->
                            PropertiesPanel(tool, document, actions, state, toolCallbacks, m)
                        }
                        if (mode == EditorLayoutMode.Expanded) {
                            Column(Modifier.width(340.dp).fillMaxHeight()) {
                                props(Modifier.weight(1f).fillMaxWidth())
                                HorizontalDivider()
                                LayersPanel(document, actions, Modifier.weight(1f).fillMaxWidth())
                            }
                        } else {
                            Column(Modifier.width(300.dp).fillMaxHeight()) {
                                PanelTabs(state.panelTab, vm::selectPanel)
                                when (state.panelTab) {
                                    PanelTab.Properties -> props(Modifier.weight(1f).fillMaxWidth())
                                    PanelTab.Layers -> LayersPanel(document, actions, Modifier.weight(1f).fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
            SnackbarHost(
                snack,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }

    if (showSheet && mode == EditorLayoutMode.Compact) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            PanelTabs(state.panelTab, vm::selectPanel)
            val sheetMod = Modifier.fillMaxWidth().height(380.dp)
            when (state.panelTab) {
                PanelTab.Properties -> PropertiesPanel(tool, document, actions, state, toolCallbacks, sheetMod)
                PanelTab.Layers -> LayersPanel(document, actions, sheetMod)
            }
        }
    }

    state.textEditor?.let { ed ->
        TextEditorDialog(
            initial = ed.initial, isNew = ed.layerId == null, docWidth = document.width,
            onDismiss = vm::cancelTextEditor, onApply = vm::applyTextEditor,
        )
    }

    if (showHistory) {
        HistoryDialog(historyState, onJump = vm::jumpToHistory, onDismiss = { showHistory = false })
    }

    resizeKind?.let { kind ->
        ResizeDialog(
            kind = kind, config = document.config,
            onDismiss = { resizeKind = null },
            onApply = { cfg, anchor ->
                if (kind == ResizeKind.Canvas) vm.resizeCanvas(cfg, anchor) else vm.resizeImage(cfg)
                resizeKind = null
            },
        )
    }
}

@Composable
private fun PanelTabs(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    TabRow(selectedTabIndex = selected.ordinal) {
        PanelTab.entries.forEach { tab ->
            Tab(selected = selected == tab, onClick = { onSelect(tab) }, text = { Text(tab.label) })
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    compact: Boolean,
    importEnabled: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onCanvasSize: () -> Unit,
    onImageSize: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    undoLabel: String?,
    redoLabel: String?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onHistory: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Enabled only when there is something to undo/redo; export stays disabled until its phase.
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, if (undoLabel != null) "Undo $undoLabel" else "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, if (redoLabel != null) "Redo $redoLabel" else "Redo")
            }
            if (compact) {
                IconButton(onClick = {}, enabled = false) { Icon(Icons.Default.FileUpload, "Export") }
            } else {
                IconButton(onClick = onImport, enabled = importEnabled) { Icon(Icons.Default.FileDownload, "Import image") }
                IconButton(onClick = {}, enabled = false) { Icon(Icons.Default.FileUpload, "Export") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Canvas size…") }, leadingIcon = { Icon(Icons.Default.AspectRatio, null) },
                        onClick = { menu = false; onCanvasSize() },
                    )
                    DropdownMenuItem(
                        text = { Text("Image size…") }, leadingIcon = { Icon(Icons.Default.PhotoSizeSelectLarge, null) },
                        onClick = { menu = false; onImageSize() },
                    )
                    DropdownMenuItem(
                        text = { Text("History…") }, leadingIcon = { Icon(Icons.Default.History, null) },
                        onClick = { menu = false; onHistory() },
                    )
                    if (compact) {
                        DropdownMenuItem(
                            text = { Text("Import image") }, enabled = importEnabled,
                            onClick = { menu = false; onImport() },
                            leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menu = false; onSettings() },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                        )
                    }
                }
            }
        }
    }
}
