package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.text.TextEngine
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Text is laid out by [TextEngine] (cached) and drawn as glyphs under the layer transform: never a bitmap. */
internal fun DrawScope.drawTextLayer(layer: Layer, spec: TextSpec, viewScale: Float) {
    val result = TextEngine.layoutFor(spec)
    val grouped = layer.opacity < 0.999f
    withLayerTransform(layer, viewScale) {
        drawIntoCanvas { canvas ->
            if (grouped) {
                val pad = spec.fontSize * 0.5f
                canvas.saveLayer(Rect(-pad, -pad, result.width + pad, result.height + pad), Paint().also { it.alpha = layer.opacity })
            }
            result.layout.draw(canvas.nativeCanvas)
            if (grouped) canvas.restore()
        }
    }
}

/**
 * Shapes ignore the layer's scale for stroke and corner radius: the scale only sizes the box, and the
 * geometry is drawn at that size. So strokes keep their width and rounded corners stay circular
 * however the layer is resized. Flip and rotation still apply.
 */
internal fun DrawScope.drawShapeLayer(layer: Layer, spec: ShapeSpec, viewScale: Float) {
    val t = layer.transform
    val w = layer.width * t.scaleX
    val h = layer.height * t.scaleY
    val fx = if (t.flipH) -1f else 1f
    val fy = if (t.flipV) -1f else 1f
    val grouped = layer.opacity < 0.999f
    withTransform({
        translate(t.x * viewScale, t.y * viewScale)
        rotate(t.rotation, Offset.Zero)
        scale(viewScale * fx, viewScale * fy, Offset.Zero)
        translate(-w / 2f, -h / 2f)
    }) {
        if (grouped) drawIntoCanvas { it.saveLayer(Rect(-2f, -2f, w + 2f, h + 2f), Paint().also { p -> p.alpha = layer.opacity }) }
        drawShape(spec, w, h)
        if (grouped) drawIntoCanvas { it.restore() }
    }
}

/** Draws [spec] inside the box (0,0)-(w,h). Strokes sit inside the box so the box is the visual extent. */
fun DrawScope.drawShape(spec: ShapeSpec, w: Float, h: Float) {
    val sw = spec.strokeWidth.coerceAtLeast(0f)
    val fill = Color(spec.fillColor)
    val stroke = Color(spec.strokeColor)

    if (spec.type == ShapeType.Line) {
        val lw = sw.coerceAtLeast(1f)
        val inset = if (spec.roundCaps) lw / 2f else 0f
        if (stroke.alpha > 0f) {
            drawLine(
                stroke, Offset(inset, h / 2f), Offset((w - inset).coerceAtLeast(inset), h / 2f), lw,
                if (spec.roundCaps) StrokeCap.Round else StrokeCap.Butt,
            )
        }
        return
    }

    val half = sw / 2f
    val rw = (w - sw).coerceAtLeast(0f)
    val rh = (h - sw).coerceAtLeast(0f)
    val topLeft = Offset(half, half)
    val size = Size(rw, rh)
    val strokeStyle = Stroke(width = sw, join = StrokeJoin.Round)
    val hasStroke = sw > 0f && stroke.alpha > 0f

    when (spec.type) {
        ShapeType.Rectangle, ShapeType.RoundedRectangle -> {
            val r = CornerRadius(spec.cornerRadius.coerceIn(0f, min(rw, rh) / 2f))
            if (fill.alpha > 0f) drawRoundRect(fill, topLeft, size, r)
            if (hasStroke) drawRoundRect(stroke, topLeft, size, r, style = strokeStyle)
        }
        ShapeType.Circle, ShapeType.Ellipse -> {
            if (fill.alpha > 0f) drawOval(fill, topLeft, size)
            if (hasStroke) drawOval(stroke, topLeft, size, style = strokeStyle)
        }
        ShapeType.Polygon, ShapeType.Star -> {
            val path = polygonPath(spec.type == ShapeType.Star, spec.sides, half, half, rw, rh)
            if (fill.alpha > 0f) drawPath(path, fill)
            if (hasStroke) drawPath(path, stroke, style = strokeStyle)
        }
        ShapeType.Line -> {}
    }
}

/** Regular polygon / star inscribed in the box; first vertex points up. */
private fun polygonPath(star: Boolean, n: Int, left: Float, top: Float, w: Float, h: Float): Path {
    val cx = left + w / 2f
    val cy = top + h / 2f
    val rx = w / 2f
    val ry = h / 2f
    val count = if (star) n * 2 else n
    val path = Path()
    for (i in 0 until count) {
        val a = -Math.PI / 2 + 2 * Math.PI * i / count
        val k = if (star && i % 2 == 1) 0.45f else 1f
        val x = cx + (cos(a) * rx * k).toFloat()
        val y = cy + (sin(a) * ry * k).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
