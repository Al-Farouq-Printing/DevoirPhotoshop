package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.paint.ActiveStroke
import com.devoir.photoshop.engine.paint.BrushMode
import com.devoir.photoshop.model.BackgroundType
import kotlin.math.roundToInt

/**
 * Draws the document from its real layer stack, bottom to top. Image and paint layers are both
 * independent bitmaps; nothing is flattened. A stroke in progress is previewed on its own layer
 * inside an isolated group so layer opacity and erasing look exactly like the committed result.
 */
fun DrawScope.drawDocument(
    doc: CanvasDocument,
    images: Map<BitmapResource, ImageBitmap>,
    scale: Float,
    offset: Offset,
    checker: Brush,
    selectionColor: Color?,
    activeStroke: ActiveStroke?,
    strokeImage: ImageBitmap?,
    adjust: AdjustRenderContext,
) {
    val w = doc.width * scale
    val h = doc.height * scale
    val size = Size(w, h)

    drawRect(Color.Black.copy(alpha = 0.28f), topLeft = offset + Offset(0f, 3f), size = size)

    withTransform({ translate(offset.x, offset.y) }) {
        if (!doc.background.visible) {
            drawRect(checker, size = size)
        } else when (doc.config.backgroundType) {
            BackgroundType.Transparent -> drawRect(checker, size = size)
            BackgroundType.White -> drawRect(Color.White, size = size)
            BackgroundType.Custom -> drawRect(Color(doc.config.backgroundColor), size = size)
        }
    }

    withTransform({ translate(offset.x, offset.y); clipRect(0f, 0f, w, h) }) {
        for (layer in doc.layers) {
            if (!layer.visible) continue
            when (val c = layer.content) {
                is LayerContent.Text -> { drawTextLayer(layer, c.spec, scale); continue }
                is LayerContent.Shape -> { drawShapeLayer(layer, c.spec, scale); continue }
                else -> {}
            }
            val res = layer.content.resource ?: continue // empty paint layer: nothing to draw yet
            if (res.isReleased) continue
            val image = images[res] ?: continue
            val dst = IntSize(layer.width.roundToInt().coerceAtLeast(1), layer.height.roundToInt().coerceAtLeast(1))
            withLayerTransform(layer, scale) {
                val stroke = if (activeStroke?.layerId == layer.id) activeStroke else null
                if (stroke != null && strokeImage != null) {
                    drawIntoCanvas { it.saveLayer(Rect(0f, 0f, layer.width, layer.height), Paint().also { p -> p.alpha = layer.opacity }) }
                    drawImage(image, dstSize = dst, filterQuality = FilterQuality.Medium)
                    drawImage(
                        strokeImage, dstSize = dst, alpha = stroke.opacity,
                        blendMode = if (stroke.mode == BrushMode.Erase) BlendMode.DstOut else BlendMode.SrcOver,
                        filterQuality = FilterQuality.Medium,
                    )
                    drawIntoCanvas { it.restore() }
                } else {
                    // Adjustments are applied here, at draw time, so visibility, opacity, transform and order
                    // keep working and the original bitmap is never modified.
                    val adj = (layer.content as? LayerContent.Image)?.adjustments
                    val shown = if (adj != null && !adj.tonal().isIdentity) adjust.processedFor(layer.id) ?: image else image
                    val filter = if (adj != null && !adj.isColorIdentity) adjust.filterFor(adj) else null
                    drawImage(shown, dstSize = dst, alpha = layer.opacity, colorFilter = filter, filterQuality = FilterQuality.Medium)
                }
            }
        }
    }

    val selected = doc.selectedLayer
    if (selectionColor != null && selected != null && selected.visible) {
        drawLayerBox(selected, scale, offset, selectionColor, showHandles = false, rotOffsetPx = 0f)
    }
}

/** Origin -> layer top-left, applying position, rotation, scale and flips about the layer centre. */
internal fun DrawScope.withLayerTransform(layer: Layer, viewScale: Float, block: DrawScope.() -> Unit) {
    val t = layer.transform
    val sx = viewScale * t.scaleX * (if (t.flipH) -1f else 1f)
    val sy = viewScale * t.scaleY * (if (t.flipV) -1f else 1f)
    withTransform({
        translate(t.x * viewScale, t.y * viewScale)
        rotate(t.rotation, Offset.Zero)
        scale(sx, sy, Offset.Zero) // negative factors = flips; the bitmap itself is never modified
        translate(-layer.width / 2f, -layer.height / 2f)
    }) { block() }
}
