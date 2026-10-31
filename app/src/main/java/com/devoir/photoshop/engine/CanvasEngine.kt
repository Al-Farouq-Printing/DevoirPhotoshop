package com.devoir.photoshop.engine

import android.graphics.Bitmap
import android.os.SystemClock
import com.devoir.photoshop.engine.adjust.AdjustmentProcessor
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.adjust.FilterPreset
import com.devoir.photoshop.engine.history.DocumentResources
import com.devoir.photoshop.engine.history.DocumentStateCommand
import com.devoir.photoshop.engine.history.HistoryContext
import com.devoir.photoshop.engine.history.HistoryManager
import com.devoir.photoshop.engine.history.HistoryUiState
import com.devoir.photoshop.engine.history.ResourceLedger
import com.devoir.photoshop.engine.history.StrokeCommand
import com.devoir.photoshop.engine.layers.Anchor
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.layers.DocumentOps
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.engine.layers.LayerResult
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.engine.layers.ShapeBounds
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.TextSize
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.paint.ActiveStroke
import com.devoir.photoshop.engine.paint.BrushEngine
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.engine.paint.BrushSettings
import com.devoir.photoshop.engine.paint.LayerGeometry
import com.devoir.photoshop.engine.paint.PaintSurfaceFactory
import com.devoir.photoshop.engine.text.TextEngine
import com.devoir.photoshop.model.ImportedImageInfo
import com.devoir.photoshop.model.ProjectConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Main-thread only: every mutation, every undo/redo and every render read happens on the main thread,
 * so the document can never be changed while it is being read, and undo can never run "at the same
 * time" as a stroke (a stroke in progress is cancelled first).
 *
 * All document changes go through [commitDocument], which keeps the bitmap ledger right, and all
 * user-visible edits are recorded in [history] first.
 */
class CanvasEngine(config: ProjectConfig, scope: CoroutineScope) : EditorEngine {
    private val ledger = ResourceLedger()
    private val _document = MutableStateFlow(CanvasDocument(config))
    override val document: StateFlow<CanvasDocument> = _document.asStateFlow()

    private val _activeStroke = MutableStateFlow<ActiveStroke?>(null)
    override val activeStroke: StateFlow<ActiveStroke?> = _activeStroke.asStateFlow()
    private val _strokeTick = MutableStateFlow(0L)
    override val strokeTick: StateFlow<Long> = _strokeTick.asStateFlow()

    private val _adjustTick = MutableStateFlow(0L)
    override val adjustTick: StateFlow<Long> = _adjustTick.asStateFlow()
    override var onNotice: ((String) -> Unit)? = null

    private val history = HistoryManager(
        ledger,
        maxPatchBytes = min(HistoryManager.DEFAULT_MAX_PATCH_BYTES, Runtime.getRuntime().maxMemory() / 8),
    )
    private val _historyState = MutableStateFlow(history.snapshot())
    override val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()
    override val canUndo: Boolean get() = history.canUndo
    override val canRedo: Boolean get() = history.canRedo
    private val historyContext = object : HistoryContext {
        override fun install(doc: CanvasDocument) = commitDocument(doc)
    }

    private val processor = AdjustmentProcessor(
        scope = scope,
        remainingBytes = { remainingBitmapBytes },
        onChanged = { _adjustTick.value++ },
        notice = { onNotice?.invoke(it) },
    )

    private val brush = BrushEngine()
    private var strokeLayerId: String? = null
    private var strokeBefore: CanvasDocument? = null
    private var strokeMode = BrushMode.Paint
    private var layerCount = 0
    private var imageCount = 0
    private var shapeCount = 0

    override val bitmapBudgetBytes: Long = Runtime.getRuntime().maxMemory() / 2

    /** Everything that holds native memory: layer bitmaps (including ones only history still needs), strokes' overlay, caches, pixel backups. */
    override val usedBitmapBytes: Long
        get() = ledger.bytes() + brush.overlayBytes + processor.bytes + history.patchBytes

    override val remainingBitmapBytes: Long get() = (bitmapBudgetBytes - usedBitmapBytes).coerceAtLeast(0L)

    // ------------------------------------------------------------------ document plumbing

    /** The single place the current document changes. New bitmaps are held before old ones are dropped. */
    private fun commitDocument(doc: CanvasDocument) {
        val old = _document.value
        if (doc === old) return
        ledger.hold(DocumentResources.of(doc))
        _document.value = doc
        ledger.drop(DocumentResources.of(old))
        syncProcessor(doc)
    }

    private fun publishHistory() { _historyState.value = history.snapshot() }

    /** An undoable edit. Recorded BEFORE the swap so the old document's bitmaps are still held. */
    private fun applyEdit(label: String, mergeKey: String? = null, op: (CanvasDocument) -> LayerResult): String? {
        val before = _document.value
        val result = op(before)
        val after = result.document
        if (after !== before && after != before) {
            history.record(DocumentStateCommand(label, before, after), mergeKey, SystemClock.uptimeMillis())
            commitDocument(after)
            publishHistory()
        }
        return result.error
    }

    /** A change that is not an action to undo (selection). */
    private fun applyQuiet(op: (CanvasDocument) -> LayerResult): String? {
        val before = _document.value
        val result = op(before)
        if (result.document !== before && result.document != before) commitDocument(result.document)
        return result.error
    }

    /** Keeps the pixel-stage cache in step with the layers: request what is needed, drop the rest. */
    private fun syncProcessor(doc: CanvasDocument) {
        val live = HashSet<String>()
        for (layer in doc.layers) {
            val content = layer.content as? LayerContent.Image ?: continue
            val tonal = content.adjustments.tonal()
            if (tonal.isIdentity) continue
            live.add(layer.id)
            processor.request(layer.id, content.resource, tonal)
        }
        processor.retainOnly(live)
    }

    private fun newId() = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ layer edits

    override fun addImageLayer(
        resource: BitmapResource, info: ImportedImageInfo, placement: ImagePlacement, name: String?,
    ): String? {
        val layerName = name?.trim()?.takeIf { it.isNotEmpty() }?.take(LayerManager.MAX_NAME_LENGTH)
            ?: "Image ${++imageCount}"
        return applyEdit("Import image") { LayerManager.addImageLayer(it, newId(), layerName, resource, info, placement) }
    }

    override fun addPaintLayer(): String? = applyEdit("Add paint layer") { LayerManager.addPaintLayer(it, newId(), "Layer ${++layerCount}") }
    override fun selectLayer(id: String) = applyQuiet { LayerManager.select(it, id) }
    override fun duplicateLayer(id: String) = applyEdit("Duplicate layer") { LayerManager.duplicate(it, id, newId()) }
    override fun renameLayer(id: String, name: String) = applyEdit("Rename layer") { LayerManager.rename(it, id, name) }
    override fun setLayerVisible(id: String, visible: Boolean) =
        applyEdit(if (visible) "Show layer" else "Hide layer") { LayerManager.setVisible(it, id, visible) }
    override fun setLayerOpacity(id: String, opacity: Float) =
        applyEdit("Layer opacity", "opacity:$id") { LayerManager.setOpacity(it, id, opacity) }
    override fun setLayerLocked(id: String, locked: Boolean) =
        applyEdit(if (locked) "Lock layer" else "Unlock layer") { LayerManager.setLocked(it, id, locked) }
    override fun moveLayer(id: String, direction: MoveDirection) = applyEdit("Reorder layer") { LayerManager.move(it, id, direction) }
    override fun setLayerTransform(id: String, transform: LayerTransform) =
        applyEdit("Transform layer", "transform:$id") { LayerManager.setTransform(it, id, transform) }
    override fun translateLayer(id: String, dx: Float, dy: Float) =
        applyEdit("Move layer", "transform:$id") { LayerManager.translate(it, id, dx, dy) }
    override fun flipLayer(id: String, horizontal: Boolean) =
        applyEdit(if (horizontal) "Flip horizontally" else "Flip vertically") { LayerManager.flip(it, id, horizontal) }
    override fun resetLayerTransform(id: String) = applyEdit("Reset transform") { LayerManager.resetTransform(it, id) }

    override fun deleteLayer(id: String): String? {
        if (id == strokeLayerId) cancelStroke()
        val error = applyEdit("Delete layer") { LayerManager.delete(it, id) }
        // Drop the big overlay buffer once no paint layer owns pixels any more (it is rebuilt on demand).
        if (_document.value.layers.none { it.type == LayerType.Paint && it.content.resource != null }) brush.releaseOverlay()
        return error
    }

    override fun cropCanvas(left: Int, top: Int, width: Int, height: Int): String? {
        cancelStroke()
        return applyEdit("Crop") { DocumentOps.crop(it, left, top, width, height) }
    }
    override fun resizeCanvas(newConfig: ProjectConfig, anchor: Anchor): String? {
        cancelStroke()
        return applyEdit("Resize canvas") { DocumentOps.resizeCanvas(it, newConfig, anchor) }
    }
    override fun resizeImage(newConfig: ProjectConfig): String? {
        cancelStroke()
        return applyEdit("Resize image") { DocumentOps.resizeImage(it, newConfig) }
    }

    override fun setLayerAdjustments(id: String, state: AdjustmentState): String? {
        val current = (_document.value.layer(id)?.content as? LayerContent.Image)?.adjustments
        val s = state.sanitized()
        // changing only the filter is its own kind of entry, so slider drags and filter picks never merge
        val filterOnly = current != null && current.withoutFilter() == s.withoutFilter() && current.filter != s.filter
        val label = when {
            !filterOnly -> "Adjust image"
            s.filter == FilterPreset.Original -> "Remove filter"
            else -> "Apply filter: ${s.filter.label}"
        }
        return applyEdit(label, if (filterOnly) "filter:$id" else "adjust:$id") { LayerManager.setAdjustments(it, id, s) }
    }

    override fun processedBitmap(layerId: String): Bitmap? = processor.processed(layerId)?.bitmap

    override fun addTextLayer(spec: TextSpec, left: Float, top: Float): String? {
        val s = spec.sanitized()
        val r = TextEngine.layoutFor(s)
        return applyEdit("Add text") { LayerManager.addTextLayer(it, newId(), s, TextSize(r.width, r.height), left, top) }
    }
    override fun updateText(id: String, spec: TextSpec): String? {
        val s = spec.sanitized()
        val r = TextEngine.layoutFor(s)
        return applyEdit("Edit text", "text:$id") { LayerManager.updateText(it, id, s, TextSize(r.width, r.height)) }
    }
    override fun addShapeLayer(spec: ShapeSpec, bounds: ShapeBounds): String? {
        val s = spec.sanitized()
        return applyEdit("Add shape") { LayerManager.addShapeLayer(it, newId(), "${s.type.label} ${++shapeCount}", s, bounds) }
    }
    override fun updateShape(id: String, spec: ShapeSpec) =
        applyEdit("Edit shape", "shape:$id") { LayerManager.updateShape(it, id, spec.sanitized()) }
    override fun setShapeBounds(id: String, bounds: ShapeBounds) =
        applyEdit("Add shape") { LayerManager.setShapeBounds(it, id, bounds) }

    // ------------------------------------------------------------------ history

    override fun beginHistoryGroup(label: String) { history.beginGroup(label) }

    override fun endHistoryGroup() {
        history.endGroup()
        publishHistory()
    }

    override fun markSaved() {
        history.markSaved()
        publishHistory()
    }

    override fun markUnsaved() {
        history.markUnsaved()
        publishHistory()
    }

    override fun trimHistoryFor(bytes: Long) {
        while (remainingBitmapBytes < bytes && history.dropOldest()) { /* keep dropping the oldest */ }
        publishHistory()
    }

    /** A stroke in progress is undone first, an open group is closed: history never runs against a half-finished edit. */
    private fun finishTransient() {
        if (brush.isActive || strokeLayerId != null) cancelStroke()
        history.endGroup()
    }

    private fun afterHistory() {
        _strokeTick.value++ // redraw (pixels may have changed)
        publishHistory()
    }

    override fun undo(): String? {
        finishTransient()
        val error = history.undo(historyContext)
        afterHistory()
        return error
    }

    override fun redo(): String? {
        finishTransient()
        val error = history.redo(historyContext)
        afterHistory()
        return error
    }

    override fun jumpToHistory(index: Int): String? {
        finishTransient()
        val error = history.jumpTo(index, historyContext)
        afterHistory()
        return error
    }

    // ------------------------------------------------------------------ painting

    override fun beginStroke(mode: BrushMode, settings: BrushSettings, docX: Float, docY: Float): StrokeStartResult {
        if (brush.isActive) cancelStroke()
        val before = _document.value
        var doc = before
        var notice: String? = null

        val selected = doc.selectedLayer
        if (selected == null || selected.type != LayerType.Paint) {
            // Never paint destructively on image/background content.
            if (mode == BrushMode.Erase) {
                return StrokeStartResult("The Eraser only works on paint layers. Select a paint layer first.")
            }
            val r = LayerManager.addPaintLayerAbove(doc, newId(), "Layer ${++layerCount}", doc.selectedLayerId)
            if (r.error != null) return StrokeStartResult(r.error)
            doc = r.document
            commitDocument(doc)
            notice = "Painting on a new paint layer"
        } else {
            if (selected.locked) return StrokeStartResult("Layer is locked — unlock it to paint.")
            if (!selected.visible) return StrokeStartResult("Layer is hidden — show it to paint.")
        }

        /** Puts back exactly what the stroke changed so far (a layer or buffer created for it). */
        fun refuse(message: String): StrokeStartResult {
            commitDocument(before)
            return StrokeStartResult(message)
        }

        var layer = doc.selectedLayer ?: return refuse("No layer to paint on.")
        var resource = layer.content.resource
        if (resource == null) {
            val need = min(PaintSurfaceFactory.MAX_PAINT_PIXELS, layer.width.roundToInt().toLong() * layer.height.roundToInt()) * 8
            if (remainingBitmapBytes < need) trimHistoryFor(need)
            when (val s = PaintSurfaceFactory.create(layer.width.roundToInt(), layer.height.roundToInt(), remainingBitmapBytes)) {
                is PaintSurfaceFactory.Result.Failed -> return refuse(s.message)
                is PaintSurfaceFactory.Result.Created -> {
                    resource = s.resource
                    doc = LayerManager.attachPaintSurface(doc, layer.id, resource).document
                    commitDocument(doc)
                    layer = doc.layer(layer.id) ?: return refuse("No layer to paint on.")
                    if (s.reduced) {
                        notice = "Large canvas: painting at reduced resolution (${resource.bitmap.width} × ${resource.bitmap.height})"
                    }
                }
            }
        }

        val start = LayerGeometry.docToLocal(layer, docX, docY)
        // A paint layer keeps the extent it was created with (e.g. before the canvas was enlarged).
        val inDoc = docX in 0f..doc.width.toFloat() && docY in 0f..doc.height.toFloat()
        if (inDoc && (start.x < 0f || start.y < 0f || start.x > layer.width || start.y > layer.height)) {
            return refuse("This paint layer doesn't cover that area (the canvas was enlarged). Add a new paint layer.")
        }
        val error = brush.begin(layer.id, layer, resource, mode, settings.sanitized(), doc.width, doc.height, start)
        if (error != null) return refuse(error)

        strokeLayerId = layer.id
        strokeBefore = before
        strokeMode = mode
        _activeStroke.value = brush.active
        _strokeTick.value++
        return StrokeStartResult(null, notice)
    }

    override fun continueStroke(docX: Float, docY: Float) {
        val id = strokeLayerId ?: return
        val layer = _document.value.layer(id)
        if (layer == null) { cancelStroke(); return }
        brush.move(LayerGeometry.docToLocal(layer, docX, docY))
        _strokeTick.value++
    }

    /** One finger-down to finger-up = ONE history entry, whatever the number of touch events. */
    override fun endStroke() {
        val id = strokeLayerId ?: return
        val patch = brush.end() // composites into the layer and returns the tiles it replaced
        val patchFailed = brush.patchFailed
        strokeLayerId = null
        val before = strokeBefore
        strokeBefore = null
        val after = LayerManager.bumpRevision(_document.value, id).document
        val label = if (strokeMode == BrushMode.Erase) "Eraser stroke" else "Brush stroke"
        val now = SystemClock.uptimeMillis()
        when {
            patch != null && before != null -> history.record(StrokeCommand(label, before, after, patch), null, now)
            patchFailed -> {
                // Without a pixel backup this stroke could not be undone, and undoing older entries would
                // then be wrong. Drop the history rather than risk corrupting the document.
                history.clear()
                onNotice?.invoke("Not enough memory to keep the undo history; earlier steps were cleared.")
            }
            before != null && before != after -> history.record(DocumentStateCommand(label, before, after), null, now)
        }
        commitDocument(after)
        _activeStroke.value = null
        _strokeTick.value++
        publishHistory()
    }

    override fun cancelStroke() {
        if (strokeLayerId == null && !brush.isActive) return
        brush.cancel()
        strokeLayerId = null
        // a paint layer or pixel buffer created only for this stroke goes away again
        strokeBefore?.let { commitDocument(it) }
        strokeBefore = null
        _activeStroke.value = null
        _strokeTick.value++
    }

    override fun release() {
        cancelStroke()
        history.clear()
        ledger.releaseAll()
        processor.releaseAll()
        brush.releaseOverlay()
        _document.value = _document.value.copy(layers = emptyList(), selectedLayerId = BACKGROUND_ID)
        _historyState.value = history.snapshot()
    }
}
