package com.devoir.photoshop.feature.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.devoir.photoshop.data.InMemoryProjectRepository
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.history.HistoryUiState
import com.devoir.photoshop.engine.CanvasEngine
import com.devoir.photoshop.engine.EditorEngine
import com.devoir.photoshop.engine.ImagePlacement
import com.devoir.photoshop.engine.crop.CropAspectOption
import com.devoir.photoshop.engine.crop.CropHandle
import com.devoir.photoshop.engine.crop.CropMath
import com.devoir.photoshop.engine.crop.CropRect
import com.devoir.photoshop.engine.image.ImageLoader
import com.devoir.photoshop.engine.layers.Anchor
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.engine.layers.ShapeGeometry
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.text.TextEngine
import com.devoir.photoshop.engine.paint.ActiveStroke
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.engine.paint.BrushSettings
import com.devoir.photoshop.engine.paint.Pt
import com.devoir.photoshop.engine.transform.TransformGeometry
import com.devoir.photoshop.engine.transform.TransformSession
import com.devoir.photoshop.feature.editor.canvas.ViewportController
import com.devoir.photoshop.model.EditorTool
import com.devoir.photoshop.model.Project
import com.devoir.photoshop.model.ProjectConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

enum class PanelTab(val label: String) { Properties("Properties"), Layers("Layers") }

/** The pending (not yet applied) crop. The document is untouched until Apply. */
data class CropUiState(
    val rect: CropRect,
    val aspect: CropAspectOption = CropAspectOption.Free,
    val customRatio: Float = 1f,
)

/** An open text editor: a new layer (layerId == null) or an existing one. */
data class TextEditorState(
    val layerId: String?,
    val initial: TextSpec,
    val anchorX: Float,
    val anchorY: Float,
    /** New text with no tap position: centre it on the canvas. */
    val centered: Boolean,
)

data class EditorUiState(
    val selectedTool: EditorTool = EditorTool.Move,
    val panelTab: PanelTab = PanelTab.Layers,
    val isImporting: Boolean = false,
    val message: String? = null,
    val brush: BrushSettings = BrushSettings(),
    val eraser: BrushSettings = BrushSettings(),
    /** Keep the layer's aspect ratio when scaling (handles and numeric fields). */
    val lockRatio: Boolean = true,
    /** Non-null only while the Crop tool is active. */
    val crop: CropUiState? = null,
    val textEditor: TextEditorState? = null,
    /** Style used for the next new text (remembers the last one applied). */
    val textStyle: TextSpec = TextSpec(),
    val shapeType: ShapeType = ShapeType.Rectangle,
    /** Colours/stroke used for the next new shape (remembers the last edit). */
    val shapeStyle: ShapeSpec = ShapeSpec(),
)

class EditorViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = InMemoryProjectRepository
    private var project: Project = repo.find(saved.get<String>("projectId"))
        ?: Project("untitled", "Untitled Project", "1080 × 1080 px · 72 DPI", 0)

    val projectName: String get() = project.name

    private val engine: EditorEngine = CanvasEngine(project.config, viewModelScope)
    val document: StateFlow<CanvasDocument> = engine.document
    val activeStroke: StateFlow<ActiveStroke?> = engine.activeStroke
    val strokeTick: StateFlow<Long> = engine.strokeTick
    val adjustTick: StateFlow<Long> = engine.adjustTick
    val historyState: StateFlow<HistoryUiState> = engine.historyState

    /** Zoom/pan state; kept here so it survives navigating to Settings and back. */
    val viewport = ViewportController().also {
        it.setDocSize(Size(project.config.widthPx.toFloat(), project.config.heightPx.toFloat()))
    }

    private val loader = ImageLoader(app)
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var transformSession: TransformSession? = null
    private var transformLayerId: String? = null
    private var cropDrag: CropDrag? = null

    private class CropDrag(val handle: CropHandle, val startRect: CropRect, val start: Pt)

    init {
        TextEngine.init(app.assets)
        engine.onNotice = { report(it) }
        _state.update { it.copy(textStyle = TextSpec.defaultFor(min(project.config.widthPx, project.config.heightPx))) }
        saved.get<String>("importUri")?.let { importImage(Uri.parse(it)) }
    }

    fun selectTool(tool: EditorTool) = _state.update { s ->
        if (tool != EditorTool.Filters && tool != EditorTool.Adjustments) adjustBaseline.clear()
        val doc = engine.document.value
        // Entering Crop shows the full-canvas overlay; leaving it without Apply simply discards it.
        val crop = if (tool == EditorTool.Crop) s.crop ?: CropUiState(CropMath.full(doc.width, doc.height)) else null
        val panelTool = tool == EditorTool.Brush || tool == EditorTool.Eraser || tool == EditorTool.Transform ||
            tool == EditorTool.Crop || tool == EditorTool.Text || tool == EditorTool.Shapes ||
            tool == EditorTool.Filters || tool == EditorTool.Adjustments
        s.copy(selectedTool = tool, panelTab = if (panelTool) PanelTab.Properties else s.panelTab, crop = crop)
    }
    fun selectPanel(tab: PanelTab) = _state.update { it.copy(panelTab = tab) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun setLockRatio(v: Boolean) = _state.update { it.copy(lockRatio = v) }
    private fun report(error: String?) { if (error != null) _state.update { it.copy(message = error) } }

    // ---- brush ----
    fun updateBrush(mode: BrushMode, settings: BrushSettings) = _state.update {
        if (mode == BrushMode.Paint) it.copy(brush = settings.sanitized()) else it.copy(eraser = settings.sanitized())
    }

    fun beginStroke(x: Float, y: Float): Boolean {
        val s = _state.value
        val (mode, settings) = when (s.selectedTool) {
            EditorTool.Brush -> BrushMode.Paint to s.brush
            EditorTool.Eraser -> BrushMode.Erase to s.eraser
            else -> return false
        }
        val r = engine.beginStroke(mode, settings, x, y)
        report(r.error ?: r.notice)
        return r.error == null
    }
    fun moveStroke(x: Float, y: Float) = engine.continueStroke(x, y)
    fun endStroke() = engine.endStroke()
    fun cancelStroke() = engine.cancelStroke()

    // ---- transform tool (gesture sessions; values live in the layer, not here) ----
    fun beginTransform(x: Float, y: Float, radiusDoc: Float, rotOffsetDoc: Float): Boolean {
        val layer = engine.document.value.selectedLayer ?: return false
        if (layer.locked) { report("Layer is locked — unlock it to transform."); return false }
        if (!layer.visible) { report("Layer is hidden — show it to transform."); return false }
        val p = Pt(x, y)
        val handle = TransformGeometry.hitTest(layer, p, radiusDoc, rotOffsetDoc) ?: return false
        transformLayerId = layer.id
        transformSession = TransformSession(layer.transform, layer.width, layer.height, handle, p, _state.value.lockRatio)
        engine.beginHistoryGroup("Transform layer") // the whole drag is one history entry
        return true
    }
    fun moveTransform(x: Float, y: Float) {
        val id = transformLayerId ?: return
        val session = transformSession ?: return
        engine.setLayerTransform(id, session.update(Pt(x, y)))
    }
    fun endTransform() {
        if (transformSession != null) engine.endHistoryGroup()
        transformSession = null
        transformLayerId = null
    }

    fun flipLayer(id: String, horizontal: Boolean) = report(engine.flipLayer(id, horizontal))
    fun resetLayerTransform(id: String) = report(engine.resetLayerTransform(id))

    // ---- crop tool ----
    private fun cropRatio(c: CropUiState): Float? {
        val d = engine.document.value
        return CropMath.ratioOf(c.aspect, d.width, d.height, c.customRatio)
    }

    fun beginCrop(x: Float, y: Float, radiusDoc: Float): Boolean {
        val crop = _state.value.crop ?: return false
        val p = Pt(x, y)
        val handle = CropMath.hitTest(crop.rect, p, radiusDoc) ?: return false
        cropDrag = CropDrag(handle, crop.rect, p)
        return true
    }
    fun moveCrop(x: Float, y: Float) {
        val drag = cropDrag ?: return
        val crop = _state.value.crop ?: return
        val d = engine.document.value
        val rect = CropMath.resize(drag.startRect, drag.handle, x - drag.start.x, y - drag.start.y, d.width, d.height, cropRatio(crop))
        _state.update { it.copy(crop = it.crop?.copy(rect = rect)) }
    }
    fun endCrop() { cropDrag = null }

    fun setCropAspect(option: CropAspectOption) = _state.update { s ->
        val crop = s.crop ?: return@update s
        val d = engine.document.value
        val next = crop.copy(aspect = option)
        val ratio = CropMath.ratioOf(option, d.width, d.height, next.customRatio)
        s.copy(crop = if (ratio != null) next.copy(rect = CropMath.fitAspect(crop.rect, ratio, d.width, d.height)) else next)
    }

    fun setCustomCropRatio(w: Float, h: Float) {
        if (w <= 0f || h <= 0f || !w.isFinite() || !h.isFinite()) return
        _state.update { s ->
            val crop = s.crop ?: return@update s
            val d = engine.document.value
            val ratio = w / h
            s.copy(crop = crop.copy(aspect = CropAspectOption.Custom, customRatio = ratio,
                rect = CropMath.fitAspect(crop.rect, ratio, d.width, d.height)))
        }
    }

    fun applyCrop() {
        val crop = _state.value.crop ?: return
        val d = engine.document.value
        val left = crop.rect.left.roundToInt().coerceIn(0, d.width - 1)
        val top = crop.rect.top.roundToInt().coerceIn(0, d.height - 1)
        val w = crop.rect.width.roundToInt().coerceIn(1, d.width - left)
        val h = crop.rect.height.roundToInt().coerceIn(1, d.height - top)
        val unchanged = left == 0 && top == 0 && w == d.width && h == d.height
        if (!unchanged) {
            val error = engine.cropCanvas(left, top, w, h)
            if (error != null) { report(error); return }
            afterDocumentSizeChanged()
        }
        _state.update { it.copy(crop = null, selectedTool = EditorTool.Move) }
    }

    /** Cancel leaves the document exactly as it was. */
    fun cancelCrop() {
        cropDrag = null
        _state.update { it.copy(crop = null, selectedTool = EditorTool.Move) }
    }

    // ---- resize ----
    fun resizeCanvas(config: ProjectConfig, anchor: Anchor) {
        val error = engine.resizeCanvas(config, anchor)
        if (error != null) report(error) else afterDocumentChange()
    }
    fun resizeImage(config: ProjectConfig) {
        val error = engine.resizeImage(config)
        if (error != null) report(error) else afterDocumentChange()
    }

    private fun afterDocumentChange() {
        afterDocumentSizeChanged()
        // any pending crop was drawn for the old size
        _state.update { s -> if (s.crop != null) s.copy(crop = null, selectedTool = EditorTool.Move) else s }
    }

    private fun afterDocumentSizeChanged() {
        val d = engine.document.value
        viewport.setDocSize(Size(d.width.toFloat(), d.height.toFloat()))
        project = project.copy(config = d.config, subtitle = "${d.width} × ${d.height} px · ${d.config.dpi} DPI")
        repo.update(project)
    }

    // ---- text ----
    private fun openNewText(at: Pt?) = _state.update {
        it.copy(textEditor = TextEditorState(null, it.textStyle.copy(text = ""), at?.x ?: 0f, at?.y ?: 0f, centered = at == null))
    }
    fun newText() = openNewText(null)

    fun openEditText(id: String) {
        val layer = engine.document.value.layer(id)
        val spec = (layer?.content as? LayerContent.Text)?.spec ?: return
        if (layer.locked) { report("Layer is locked — unlock it to edit its text."); return }
        _state.update { it.copy(textEditor = TextEditorState(id, spec, 0f, 0f, centered = false)) }
    }

    fun cancelTextEditor() = _state.update { it.copy(textEditor = null) }

    fun applyTextEditor(spec: TextSpec) {
        val ed = _state.value.textEditor ?: return
        val s = spec.sanitized()
        if (s.text.isBlank()) { report("Text can't be empty"); return }
        val error = if (ed.layerId == null) {
            val doc = engine.document.value
            val r = TextEngine.layoutFor(s)
            val left = if (ed.centered) doc.width / 2f - r.width / 2f else ed.anchorX
            val top = if (ed.centered) doc.height / 2f - r.height / 2f else ed.anchorY
            engine.addTextLayer(s, left, top)
        } else {
            engine.updateText(ed.layerId, s) // same layer, same id: edited in place
        }
        if (error != null) { report(error); return }
        _state.update { it.copy(textEditor = null, textStyle = s.copy(text = "")) }
    }

    /** Immediate edits from the Properties panel. */
    fun updateTextSpec(id: String, spec: TextSpec) = report(engine.updateText(id, spec))

    /** Text tool tap: edit the text under the finger, otherwise start new text there. */
    fun tapText(x: Float, y: Float) {
        val slop = TEXT_TAP_SLOP_PX / viewport.scale
        val hit = engine.document.value.layers.asReversed().firstOrNull { l ->
            l.visible && l.content is LayerContent.Text && TransformGeometry.docToBox(l, Pt(x, y)).let { b ->
                val h = TransformGeometry.halfSize(l)
                abs(b.x) <= h.x + slop && abs(b.y) <= h.y + slop
            }
        }
        if (hit != null) { report(engine.selectLayer(hit.id)); openEditText(hit.id) } else openNewText(Pt(x, y))
    }

    // ---- shapes ----
    private class ShapeDrag(val start: Pt, var layerId: String? = null)
    private var shapeDrag: ShapeDrag? = null

    fun setShapeType(t: ShapeType) = _state.update { it.copy(shapeType = t) }
    private fun newShapeSpec(type: ShapeType = _state.value.shapeType) = _state.value.shapeStyle.newOfType(type)

    fun beginShape(x: Float, y: Float): Boolean {
        shapeDrag = ShapeDrag(Pt(x, y))
        engine.beginHistoryGroup("Add shape") // creating by drag is one entry
        return true
    }

    fun moveShape(x: Float, y: Float) {
        val d = shapeDrag ?: return
        val spec = newShapeSpec()
        val bounds = ShapeGeometry.fromDrag(spec.type, d.start, Pt(x, y), spec.strokeWidth)
        val id = d.layerId
        if (id == null) {
            if (hypot(x - d.start.x, y - d.start.y) < SHAPE_DRAG_SLOP_PX / viewport.scale) return
            val error = engine.addShapeLayer(spec, bounds)
            if (error != null) { report(error); shapeDrag = null; engine.endHistoryGroup(); return }
            d.layerId = engine.document.value.selectedLayerId
        } else {
            engine.setShapeBounds(id, bounds)
        }
    }

    fun endShape() {
        val d = shapeDrag ?: return
        shapeDrag = null
        if (d.layerId == null) { // a plain tap: default-size shape centred on the touch
            val doc = engine.document.value
            val spec = newShapeSpec()
            report(engine.addShapeLayer(spec, ShapeGeometry.defaultBounds(spec.type, d.start, min(doc.width, doc.height) / 4f, spec.strokeWidth)))
        }
        engine.endHistoryGroup()
    }

    /** A second finger landed: throw away the shape being dragged. */
    fun cancelShape() {
        val d = shapeDrag ?: return
        shapeDrag = null
        d.layerId?.let { engine.deleteLayer(it) }
        engine.endHistoryGroup() // net effect is nothing, so no entry is kept
    }

    fun addShapeAtCenter(type: ShapeType) {
        val doc = engine.document.value
        val spec = newShapeSpec(type)
        report(engine.addShapeLayer(spec, ShapeGeometry.defaultBounds(type, Pt(doc.width / 2f, doc.height / 2f), min(doc.width, doc.height) / 4f, spec.strokeWidth)))
    }

    fun updateShapeSpec(id: String, spec: ShapeSpec) {
        val error = engine.updateShape(id, spec)
        report(error)
        if (error == null) _state.update { it.copy(shapeStyle = spec.sanitized()) }
    }

    // ---- undo / redo ----
    fun undo() { report(engine.undo()); afterHistoryNavigation() }
    fun redo() { report(engine.redo()); afterHistoryNavigation() }
    fun jumpToHistory(index: Int) { report(engine.jumpToHistory(index)); afterHistoryNavigation() }

    /** The document may have changed size (crop/resize undone) or lost the layer a pending edit pointed at. */
    private fun afterHistoryNavigation() {
        afterDocumentSizeChanged()
        val d = engine.document.value
        _state.update { s -> s.copy(crop = s.crop?.copy(rect = CropMath.full(d.width, d.height))) }
    }

    // ---- colour & filters (image layers; the original pixels are never changed) ----
    private val adjustBaseline = HashMap<String, AdjustmentState>()
    private fun adjustmentsOf(id: String) = (engine.document.value.layer(id)?.content as? LayerContent.Image)?.adjustments

    fun setLayerAdjustments(id: String, state: AdjustmentState) {
        val current = adjustmentsOf(id)
        if (current != null && id !in adjustBaseline) adjustBaseline[id] = current // for "Revert changes"
        report(engine.setLayerAdjustments(id, state))
    }
    fun resetAdjustmentValues(id: String) { adjustmentsOf(id)?.let { setLayerAdjustments(id, it.withoutValues()) } }
    fun resetFilter(id: String) { adjustmentsOf(id)?.let { setLayerAdjustments(id, it.withoutFilter()) } }
    fun resetAllAdjustments(id: String) { adjustmentsOf(id)?.let { setLayerAdjustments(id, AdjustmentState()) } }
    fun revertAdjustments(id: String) { adjustBaseline[id]?.let { report(engine.setLayerAdjustments(id, it)) } }
    fun processedBitmap(layerId: String): Bitmap? = engine.processedBitmap(layerId)

    // ---- layer operations (all rules live in the engine/LayerManager) ----
    fun selectLayer(id: String) = report(engine.selectLayer(id))
    fun setLayerVisible(id: String, v: Boolean) = report(engine.setLayerVisible(id, v))
    fun setLayerLocked(id: String, v: Boolean) = report(engine.setLayerLocked(id, v))
    fun setLayerOpacity(id: String, v: Float) = report(engine.setLayerOpacity(id, v))
    fun addPaintLayer() = report(engine.addPaintLayer())
    fun duplicateLayer(id: String) = report(engine.duplicateLayer(id))
    fun deleteLayer(id: String) = report(engine.deleteLayer(id))
    fun renameLayer(id: String, name: String) = report(engine.renameLayer(id, name))
    fun moveLayer(id: String, d: MoveDirection) = report(engine.moveLayer(id, d))
    fun setLayerTransform(id: String, t: LayerTransform) = report(engine.setLayerTransform(id, t))

    fun moveSelectedBy(dx: Float, dy: Float) {
        engine.translateLayer(engine.document.value.selectedLayerId, dx, dy)
    }

    fun importImage(uri: Uri) {
        if (_state.value.isImporting) return
        // Old undo steps may still hold a deleted layer's pixels; give them up if the new image needs the room.
        if (engine.remainingBitmapBytes < IMPORT_HEADROOM_BYTES) engine.trimHistoryFor(IMPORT_HEADROOM_BYTES)
        val remainingPixels = engine.remainingBitmapBytes / 4
        if (remainingPixels < MIN_IMPORT_PIXELS) {
            _state.update { it.copy(message = "Not enough memory for another image. Delete a layer first.") }
            return
        }
        _state.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val message = when (val r = loader.load(uri, remainingPixels)) {
                is ImageLoader.Result.Success -> {
                    val info = r.image.info
                    val doc = engine.document.value
                    val placement = ImagePlacement.fit(
                        doc.width.toFloat(), doc.height.toFloat(),
                        info.originalWidth.toFloat(), info.originalHeight.toFloat(),
                    )
                    val resource = BitmapResource(r.image.bitmap, r.image.thumbnail)
                    val error = engine.addImageLayer(resource, info, placement, info.displayName?.substringBeforeLast('.'))
                    if (error != null) {
                        resource.release()
                        error
                    } else {
                        project = project.copy(importedImage = info)
                        repo.update(project)
                        if (info.sampleSize > 1) {
                            "Imported at reduced resolution (${info.loadedWidth} × ${info.loadedHeight}) to stay within memory limits"
                        } else null
                    }
                }
                is ImageLoader.Result.Failure -> r.message
            }
            _state.update { it.copy(isImporting = false, message = message) }
        }
    }

    override fun onCleared() {
        engine.release() // cancels any stroke and frees every bitmap
        super.onCleared()
    }

    private companion object {
        const val MIN_IMPORT_PIXELS = 1_000_000L
        const val IMPORT_HEADROOM_BYTES = 64L * 1024 * 1024
        const val TEXT_TAP_SLOP_PX = 12f
        const val SHAPE_DRAG_SLOP_PX = 8f
    }
}
