@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.crop.CropRect
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.paint.ActiveStroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val ROT_OFFSET_DP = 36f

enum class InputMode {
    /** One finger pans, two fingers zoom/pan. */
    Navigate,
    /** One finger drags the selected layer; two fingers zoom/pan. */
    MoveLayer,
    /** One finger draws (brush/eraser); a second finger cancels the stroke and zooms/pans. */
    Draw,
    /** One finger on a handle/body of the selected layer transforms it; elsewhere it pans. */
    Transform,
    /** One finger on a crop handle/body edits the crop rectangle; elsewhere it pans. */
    Crop,
    /** A tap adds text (or edits the text tapped); dragging pans. */
    Text,
    /** One finger drags out a new shape; a second finger cancels it and zooms/pans. */
    Shape,
}

private enum class Capture { None, Draw, Transform, Crop, Shape }

/** Tool-dependent behaviour supplied by the editor; the canvas itself stays tool-agnostic. */
class CanvasInput(
    val mode: InputMode,
    val brushDiameterDoc: Float = 0f,
    val onLayerDrag: (dxDoc: Float, dyDoc: Float) -> Unit = { _, _ -> },
    val onStrokeStart: (xDoc: Float, yDoc: Float) -> Boolean = { _, _ -> false },
    val onStrokeMove: (xDoc: Float, yDoc: Float) -> Unit = { _, _ -> },
    val onStrokeEnd: () -> Unit = {},
    val onStrokeCancel: () -> Unit = {},
    val showTransformHandles: Boolean = false,
    val onTransformStart: (xDoc: Float, yDoc: Float, radiusDoc: Float, rotOffsetDoc: Float) -> Boolean = { _, _, _, _ -> false },
    val onTransformMove: (xDoc: Float, yDoc: Float) -> Unit = { _, _ -> },
    val onTransformEnd: () -> Unit = {},
    val onCropStart: (xDoc: Float, yDoc: Float, radiusDoc: Float) -> Boolean = { _, _, _ -> false },
    val onCropMove: (xDoc: Float, yDoc: Float) -> Unit = { _, _ -> },
    val onCropEnd: () -> Unit = {},
    val onTextTap: (xDoc: Float, yDoc: Float) -> Unit = { _, _ -> },
    val onShapeStart: (xDoc: Float, yDoc: Float) -> Boolean = { _, _ -> false },
    val onShapeMove: (xDoc: Float, yDoc: Float) -> Unit = { _, _ -> },
    val onShapeEnd: () -> Unit = {},
    val onShapeCancel: () -> Unit = {},
)

/**
 * Interactive canvas. Screen -> document conversion is `(screen - viewport.offset) / viewport.scale`;
 * the engine then converts document -> layer coordinates, so everything lands correctly at any zoom/pan.
 */
@Composable
fun CanvasView(
    document: CanvasDocument,
    viewport: ViewportController,
    input: CanvasInput,
    activeStroke: ActiveStroke?,
    strokeTick: State<Long>,
    adjustTick: State<Long>,
    processedLookup: (String) -> Bitmap?,
    crop: CropRect?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fitPaddingPx = with(density) { 24.dp.toPx() }
    val checker = rememberCheckerBrush(10.dp)
    val selectionColor = MaterialTheme.colorScheme.primary

    // One ImageBitmap wrapper per live resource (wrapping does not copy pixels). Text and shapes have none.
    val cache = remember { HashMap<BitmapResource, ImageBitmap>() }
    val images = remember(document.layers) {
        val live = document.layers.mapNotNull { it.content.resource }.filter { !it.isReleased }.toSet()
        cache.keys.retainAll(live)
        live.forEach { r -> cache.getOrPut(r) { r.bitmap.asImageBitmap() } }
        cache as Map<BitmapResource, ImageBitmap>
    }
    val strokeImage = remember(activeStroke) { activeStroke?.overlay?.asImageBitmap() }

    // Colour filters are cached; processed (tone/sharpness) bitmaps are wrapped once and pruned when unused.
    val filterCache = remember { AdjustmentFilterCache() }
    val processedWrappers = remember { HashMap<Bitmap, ImageBitmap>() }
    remember(document.layers) {
        processedWrappers.keys.retainAll(document.layers.mapNotNull { processedLookup(it.id) }.toSet())
    }
    val adjustCtx = remember(processedLookup) {
        AdjustRenderContext(
            filterFor = filterCache::get,
            processedFor = { id -> processedLookup(id)?.let { b -> processedWrappers.getOrPut(b) { b.asImageBitmap() } } },
        )
    }

    val scope = rememberCoroutineScope()
    var animJob by remember { mutableStateOf<Job?>(null) }
    val inputNow by rememberUpdatedState(input)
    var cursor by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport.setViewSize(Size(it.width.toFloat(), it.height.toFloat()), fitPaddingPx) }
            .pointerInput(viewport) {
                detectTapGestures(onDoubleTap = { p ->
                    val m = inputNow.mode
                    // double-tap must not zoom while painting dots, adding text or drawing shapes
                    if (m != InputMode.Draw && m != InputMode.Text && m != InputMode.Shape) {
                        animJob?.cancel()
                        animJob = scope.launch { viewport.animateTo(viewport.doubleTapTarget(p)) }
                    }
                })
            }
            .pointerInput(viewport) {
                awaitEachGesture {
                    fun docX(p: Offset) = (p.x - viewport.offset.x) / viewport.scale
                    fun docY(p: Offset) = (p.y - viewport.offset.y) / viewport.scale

                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cfg = inputNow
                    val hitRadiusDoc = 28.dp.toPx() / viewport.scale
                    val rotOffsetDoc = ROT_OFFSET_DP.dp.toPx() / viewport.scale
                    val x0 = docX(down.position)
                    val y0 = docY(down.position)

                    var captured = Capture.None
                    when (cfg.mode) {
                        InputMode.Draw -> if (cfg.onStrokeStart(x0, y0)) { captured = Capture.Draw; cursor = down.position }
                        InputMode.Transform -> if (cfg.onTransformStart(x0, y0, hitRadiusDoc, rotOffsetDoc)) captured = Capture.Transform
                        InputMode.Crop -> if (cfg.onCropStart(x0, y0, hitRadiusDoc)) captured = Capture.Crop
                        InputMode.Shape -> if (cfg.onShapeStart(x0, y0)) captured = Capture.Shape
                        else -> {}
                    }
                    if (captured != Capture.None) { animJob?.cancel(); down.consume() }

                    var multiTouch = false
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastSlop = false
                    val slop = viewConfiguration.touchSlop

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multiTouch = true

                        if (captured != Capture.None && multiTouch) {
                            // a second finger means "navigate": undo a stray stroke/shape, keep a transform/crop as it is
                            when (captured) {
                                Capture.Draw -> cfg.onStrokeCancel()
                                Capture.Transform -> cfg.onTransformEnd()
                                Capture.Crop -> cfg.onCropEnd()
                                Capture.Shape -> cfg.onShapeCancel()
                                Capture.None -> {}
                            }
                            captured = Capture.None
                            cursor = null
                        }

                        if (captured != Capture.None) {
                            val c = event.changes.firstOrNull { it.id == down.id }
                            if (c != null) {
                                val px = docX(c.position)
                                val py = docY(c.position)
                                when (captured) {
                                    Capture.Draw -> {
                                        // Replay batched touch samples so fast strokes stay smooth.
                                        c.historical.forEach { h -> cfg.onStrokeMove(docX(h.position), docY(h.position)) }
                                        cfg.onStrokeMove(px, py)
                                        cursor = c.position
                                    }
                                    Capture.Transform -> cfg.onTransformMove(px, py)
                                    Capture.Crop -> cfg.onCropMove(px, py)
                                    Capture.Shape -> cfg.onShapeMove(px, py)
                                    Capture.None -> {}
                                }
                                if (c.positionChanged()) c.consume()
                            }
                        } else if (multiTouch || cfg.mode != InputMode.Draw) {
                            if (event.changes.none { it.isConsumed }) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!pastSlop) {
                                    zoom *= zoomChange
                                    pan += panChange
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    if (abs(1 - zoom) * centroidSize > slop || pan.getDistance() > slop) pastSlop = true
                                }
                                if (pastSlop) {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        animJob?.cancel()
                                        if (!multiTouch && cfg.mode == InputMode.MoveLayer) {
                                            cfg.onLayerDrag(panChange.x / viewport.scale, panChange.y / viewport.scale)
                                        } else {
                                            viewport.onGesture(centroid, panChange, zoomChange)
                                        }
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    when (captured) {
                        Capture.Draw -> cfg.onStrokeEnd()
                        Capture.Transform -> cfg.onTransformEnd()
                        Capture.Crop -> cfg.onCropEnd()
                        Capture.Shape -> cfg.onShapeEnd()
                        Capture.None -> {}
                    }
                    // A clean single-finger tap (no drag, no second finger) in Text mode.
                    if (captured == Capture.None && cfg.mode == InputMode.Text && !pastSlop && !multiTouch) {
                        cfg.onTextTap(x0, y0)
                    }
                    cursor = null
                }
            }
            .semantics {
                contentDescription = "Canvas ${document.width} by ${document.height} pixels, ${document.layers.size} layers"
            },
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = strokeTick.value // subscribe: redraw while a stroke changes
        @Suppress("UNUSED_VARIABLE") val adjTick = adjustTick.value // redraw when a processed bitmap arrives
        drawDocument(
            document, images, viewport.scale, viewport.offset, checker,
            if (input.mode == InputMode.MoveLayer || input.mode == InputMode.Navigate) selectionColor else null,
            activeStroke, strokeImage, adjustCtx,
        )
        if (input.mode == InputMode.Transform) {
            val sel = document.selectedLayer
            if (sel != null && sel.visible) {
                drawLayerBox(sel, viewport.scale, viewport.offset, selectionColor, input.showTransformHandles, ROT_OFFSET_DP.dp.toPx())
            }
        }
        if (input.mode == InputMode.Crop && crop != null) {
            drawCropOverlay(crop, document.width, document.height, viewport.scale, viewport.offset)
        }
        val c = cursor
        if (c != null && input.mode == InputMode.Draw) {
            val r = (input.brushDiameterDoc / 2f * viewport.scale).coerceAtLeast(3f)
            drawCircle(Color.White, r, c, style = Stroke(width = 2.dp.toPx()))
            drawCircle(Color.Black, r, c, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
