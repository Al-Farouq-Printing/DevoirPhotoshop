package com.devoir.photoshop.engine.layers

import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.model.ProjectConfig
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Anchor(val ax: Float, val ay: Float, val label: String) {
    TopLeft(0f, 0f, "Top left"), TopCenter(0.5f, 0f, "Top"), TopRight(1f, 0f, "Top right"),
    CenterLeft(0f, 0.5f, "Left"), Center(0.5f, 0.5f, "Center"), CenterRight(1f, 0.5f, "Right"),
    BottomLeft(0f, 1f, "Bottom left"), BottomCenter(0.5f, 1f, "Bottom"), BottomRight(1f, 1f, "Bottom right"),
}

/**
 * Document-level operations. None of them touches pixels: layers keep their bitmaps and only their
 * placement changes, so memory use stays flat and nothing is resampled or flattened.
 *  - Crop / canvas resize: change the canvas bounds and shift layers. Content that falls outside is
 *    clipped by the renderer but kept, so it comes back if the canvas is enlarged or the layer moved.
 *  - Image resize: scale every layer's placement with the document (bitmaps are drawn from the
 *    original pixels at the new size, so there is no resampling pass).
 */
object DocumentOps {
    private fun isEmptyPaint(l: Layer) = l.type == LayerType.Paint && l.content.resource == null

    private fun invalidSize(w: Int, h: Int): String? = when {
        w < 1 || h < 1 -> "Canvas must be at least 1 × 1 px"
        w > ProjectMath.MAX_SIDE_PX || h > ProjectMath.MAX_SIDE_PX -> "Max ${ProjectMath.MAX_SIDE_PX} px per side"
        w.toLong() * h > ProjectMath.MAX_TOTAL_PX -> "Canvas exceeds ${ProjectMath.MAX_TOTAL_PX / 1_000_000} megapixels"
        else -> null
    }

    /** An empty paint layer has no pixels to preserve, so it simply re-covers the new canvas. */
    private fun refit(l: Layer, w: Int, h: Int): Layer {
        val t = LayerTransform(w / 2f, h / 2f)
        return l.copy(width = w.toFloat(), height = h.toFloat(), transform = t, defaultTransform = t)
    }

    private fun shifted(t: LayerTransform, dx: Float, dy: Float) = t.copy(x = t.x + dx, y = t.y + dy)

    fun changeCanvas(doc: CanvasDocument, newConfig: ProjectConfig, shiftX: Float, shiftY: Float): LayerResult {
        invalidSize(newConfig.widthPx, newConfig.heightPx)?.let { return LayerResult(doc, it) }
        val layers = doc.layers.map {
            if (isEmptyPaint(it)) refit(it, newConfig.widthPx, newConfig.heightPx)
            else it.copy(transform = shifted(it.transform, shiftX, shiftY), defaultTransform = shifted(it.defaultTransform, shiftX, shiftY))
        }
        return LayerResult(doc.copy(config = newConfig, layers = layers))
    }

    fun crop(doc: CanvasDocument, left: Int, top: Int, w: Int, h: Int): LayerResult {
        if (left < 0 || top < 0 || w < 1 || h < 1 || left + w > doc.width || top + h > doc.height) {
            return LayerResult(doc, "Crop area must be inside the canvas")
        }
        return changeCanvas(doc, ProjectMath.withPixelSize(doc.config, w, h), -left.toFloat(), -top.toFloat())
    }

    fun resizeCanvas(doc: CanvasDocument, newConfig: ProjectConfig, anchor: Anchor): LayerResult {
        val dx = ((newConfig.widthPx - doc.width) * anchor.ax).roundToInt().toFloat()
        val dy = ((newConfig.heightPx - doc.height) * anchor.ay).roundToInt().toFloat()
        return changeCanvas(doc, newConfig, dx, dy)
    }

    /** Scales a placement about the document origin. Layers turned off-axis fall back to a uniform factor. */
    private fun scaled(t: LayerTransform, fx: Float, fy: Float): LayerTransform {
        val rot = ((t.rotation % 180f) + 180f) % 180f
        val g = sqrt(fx * fy)
        val (mx, my) = when {
            rot < 0.5f || rot > 179.5f -> fx to fy
            abs(rot - 90f) < 0.5f -> fy to fx
            else -> g to g
        }
        return t.copy(x = t.x * fx, y = t.y * fy, scaleX = t.scaleX * mx, scaleY = t.scaleY * my)
    }

    /** Shapes keep stroke width and corner radius in document pixels, so those scale with the image too. */
    private fun scaleLayer(l: Layer, fx: Float, fy: Float): Layer {
        val content = (l.content as? LayerContent.Shape)?.let { c ->
            val g = sqrt(fx * fy)
            LayerContent.Shape(c.spec.copy(strokeWidth = c.spec.strokeWidth * g, cornerRadius = c.spec.cornerRadius * g))
        } ?: l.content
        return l.copy(content = content, transform = scaled(l.transform, fx, fy), defaultTransform = scaled(l.defaultTransform, fx, fy))
    }

    fun resizeImage(doc: CanvasDocument, newConfig: ProjectConfig): LayerResult {
        invalidSize(newConfig.widthPx, newConfig.heightPx)?.let { return LayerResult(doc, it) }
        val fx = newConfig.widthPx.toFloat() / doc.width
        val fy = newConfig.heightPx.toFloat() / doc.height
        val layers = doc.layers.map {
            if (isEmptyPaint(it)) refit(it, newConfig.widthPx, newConfig.heightPx)
            else scaleLayer(it, fx, fy)
        }
        return LayerResult(doc.copy(config = newConfig, layers = layers))
    }
}
