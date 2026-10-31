package com.devoir.photoshop.engine

import android.graphics.Bitmap
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.history.HistoryUiState
import com.devoir.photoshop.engine.layers.Anchor
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.ShapeBounds
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.engine.paint.ActiveStroke
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.engine.paint.BrushSettings
import com.devoir.photoshop.model.ImportedImageInfo
import com.devoir.photoshop.model.ProjectConfig
import kotlinx.coroutines.flow.StateFlow

/** [error] != null: the stroke was refused. [notice] is an informational message for the user. */
data class StrokeStartResult(val error: String?, val notice: String? = null)

/**
 * Editing-engine boundary. The UI observes [document] and calls these methods; it never mutates
 * the document directly. Mutating layer methods return an error message when refused, or null.
 */
interface EditorEngine {
    val canUndo: Boolean
    val canRedo: Boolean
    val document: StateFlow<CanvasDocument>

    /** Bytes of live layer bitmaps plus the stroke overlay. */
    val usedBitmapBytes: Long
    val bitmapBudgetBytes: Long
    val remainingBitmapBytes: Long

    /** Non-null only while a stroke is in progress (for the live preview). */
    val activeStroke: StateFlow<ActiveStroke?>

    /** Changes on every stroke update so the canvas knows to redraw. */
    val strokeTick: StateFlow<Long>

    /** Takes ownership of [resource] on success; on error the caller must release it. */
    fun addImageLayer(resource: BitmapResource, info: ImportedImageInfo, placement: ImagePlacement, name: String?): String?
    fun addPaintLayer(): String?
    fun selectLayer(id: String): String?
    fun deleteLayer(id: String): String?
    fun duplicateLayer(id: String): String?
    fun renameLayer(id: String, name: String): String?
    fun setLayerVisible(id: String, visible: Boolean): String?
    fun setLayerOpacity(id: String, opacity: Float): String?
    fun setLayerLocked(id: String, locked: Boolean): String?
    fun moveLayer(id: String, direction: MoveDirection): String?
    fun setLayerTransform(id: String, transform: LayerTransform): String?
    fun translateLayer(id: String, dx: Float, dy: Float): String?

    fun flipLayer(id: String, horizontal: Boolean): String?
    fun resetLayerTransform(id: String): String?

    // ---- history ----
    val historyState: StateFlow<HistoryUiState>

    /** Each returns an error message if it failed (the document is then unchanged), or null. */
    fun undo(): String?
    fun redo(): String?

    /** Jump to the state after [index] actions (0 = start state). */
    fun jumpToHistory(index: Int): String?

    /** Everything changed between begin and end becomes ONE history entry (e.g. a drag). */
    fun beginHistoryGroup(label: String)
    fun endHistoryGroup()

    /** Marks the current state as saved; [HistoryUiState.isModified] is false until the next edit. */
    fun markSaved()

    /** Marks the current state as NOT saved (e.g. a recovered session). */
    fun markUnsaved()

    /** Forgets the oldest history entries until [bytes] of bitmap budget are free (or history is empty). */
    fun trimHistoryFor(bytes: Long)

    // ---- colour & filters (image layers only; non-destructive) ----
    fun setLayerAdjustments(id: String, state: AdjustmentState): String?

    /** Cached result of the pixel stage (highlights/shadows/sharpness) for a layer, or null if none yet. */
    fun processedBitmap(layerId: String): Bitmap?

    /** Changes whenever a processed bitmap is published or dropped, so the canvas redraws. */
    val adjustTick: StateFlow<Long>

    /** Receives short informational messages from background processing (e.g. low memory). */
    var onNotice: ((String) -> Unit)?

    // ---- text & shapes (new layers are selected; ids are read from document.selectedLayerId) ----
    fun addTextLayer(spec: TextSpec, left: Float, top: Float): String?
    fun updateText(id: String, spec: TextSpec): String?
    fun addShapeLayer(spec: ShapeSpec, bounds: ShapeBounds): String?
    fun updateShape(id: String, spec: ShapeSpec): String?
    fun setShapeBounds(id: String, bounds: ShapeBounds): String?

    // ---- document level (no pixel copies; see DocumentOps) ----
    fun cropCanvas(left: Int, top: Int, width: Int, height: Int): String?
    fun resizeCanvas(newConfig: ProjectConfig, anchor: Anchor): String?
    fun resizeImage(newConfig: ProjectConfig): String?

    // ---- painting (coordinates are document pixels) ----
    fun beginStroke(mode: BrushMode, settings: BrushSettings, docX: Float, docY: Float): StrokeStartResult
    fun continueStroke(docX: Float, docY: Float)
    fun endStroke()
    fun cancelStroke()

    /** Frees every layer's resources. Call when the document is closed. */
    fun release()
}
