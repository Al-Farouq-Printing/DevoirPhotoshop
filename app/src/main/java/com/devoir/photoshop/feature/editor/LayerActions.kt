package com.devoir.photoshop.feature.editor

import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.crop.CropAspectOption
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.engine.paint.BrushSettings

/** Everything the layer UI can ask for; wired to the ViewModel once, in EditorScreen. */
class LayerActions(
    val onSelect: (String) -> Unit,
    val onSetVisible: (String, Boolean) -> Unit,
    val onSetLocked: (String, Boolean) -> Unit,
    val onSetOpacity: (String, Float) -> Unit,
    val onAddPaint: () -> Unit,
    val onAddImage: () -> Unit,
    val onDuplicate: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onMove: (String, MoveDirection) -> Unit,
    val onTransform: (String, LayerTransform) -> Unit,
    val onFlip: (String, Boolean) -> Unit,
    val onResetTransform: (String) -> Unit,
    val onAddText: () -> Unit,
    val onAddShape: (ShapeType) -> Unit,
    val onEditText: (String) -> Unit,
    val onUpdateText: (String, TextSpec) -> Unit,
    val onUpdateShape: (String, ShapeSpec) -> Unit,
    val onSetAdjustments: (String, AdjustmentState) -> Unit,
    /** Sliders back to 0, filter kept. */
    val onResetAdjustValues: (String) -> Unit,
    /** Filter back to Original, sliders kept. */
    val onResetFilter: (String) -> Unit,
    /** Filter and sliders. */
    val onResetAllAdjust: (String) -> Unit,
    /** Back to how the layer looked when the Filters/Adjustments tool was opened. */
    val onRevertAdjust: (String) -> Unit,
)

class CropActions(
    val onAspect: (CropAspectOption) -> Unit,
    val onCustomRatio: (Float, Float) -> Unit,
    val onApply: () -> Unit,
    val onCancel: () -> Unit,
)

/** Tool-level callbacks for the Properties panel. */
class ToolCallbacks(
    val onBrushChange: (BrushMode, BrushSettings) -> Unit,
    val onLockRatio: (Boolean) -> Unit,
    val onShapeType: (ShapeType) -> Unit,
    val onNewText: () -> Unit,
    val crop: CropActions,
)
