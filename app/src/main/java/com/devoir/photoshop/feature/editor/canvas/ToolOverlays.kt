package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.crop.CropRect
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.paint.Pt
import com.devoir.photoshop.engine.transform.TransformGeometry

/**
 * UI-only overlays, drawn in screen space after the document. They are never part of any layer.
 * Screen position = viewport.offset + documentPoint * viewport.scale (same mapping the gestures invert).
 */
fun DrawScope.drawLayerBox(
    layer: Layer, scale: Float, offset: Offset, color: Color, showHandles: Boolean, rotOffsetPx: Float,
) {
    fun s(p: Pt) = Offset(offset.x + p.x * scale, offset.y + p.y * scale)
    val c = TransformGeometry.corners(layer).map(::s)
    val thick = 3.dp.toPx()
    val thin = 1.5f.dp.toPx()
    for (i in 0..3) {
        drawLine(Color.White, c[i], c[(i + 1) % 4], thick)
        drawLine(color, c[i], c[(i + 1) % 4], thin)
    }
    if (!showHandles) return

    val half = TransformGeometry.halfSize(layer)
    val topMid = s(TransformGeometry.boxToDoc(layer, 0f, -half.y))
    val rot = s(TransformGeometry.rotationHandle(layer, rotOffsetPx / scale))
    drawLine(color, topMid, rot, thin)
    drawCircle(Color.White, 9.dp.toPx(), rot)
    drawCircle(color, 9.dp.toPx(), rot, style = Stroke(thin))

    val hs = 12.dp.toPx()
    for (p in c) {
        val tl = Offset(p.x - hs / 2f, p.y - hs / 2f)
        drawRect(Color.White, tl, Size(hs, hs))
        drawRect(color, tl, Size(hs, hs), style = Stroke(thin))
    }
}

fun DrawScope.drawCropOverlay(rect: CropRect, docW: Int, docH: Int, scale: Float, offset: Offset) {
    val dl = offset.x
    val dt = offset.y
    val dr = dl + docW * scale
    val db = dt + docH * scale
    val l = dl + rect.left * scale
    val t = dt + rect.top * scale
    val r = dl + rect.right * scale
    val b = dt + rect.bottom * scale

    val dim = Color.Black.copy(alpha = 0.55f)
    drawRect(dim, Offset(dl, dt), Size(dr - dl, t - dt))
    drawRect(dim, Offset(dl, b), Size(dr - dl, db - b))
    drawRect(dim, Offset(dl, t), Size(l - dl, b - t))
    drawRect(dim, Offset(r, t), Size(dr - r, b - t))

    val thin = 1.dp.toPx()
    val grid = Color.White.copy(alpha = 0.45f)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(grid, Offset(x, t), Offset(x, b), thin)
        drawLine(grid, Offset(l, y), Offset(r, y), thin)
    }
    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))

    val hs = 14.dp.toPx()
    val es = 9.dp.toPx()
    val points = listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))
    for (p in points) drawRect(Color.White, Offset(p.x - hs / 2f, p.y - hs / 2f), Size(hs, hs))
    val mids = listOf(Offset((l + r) / 2f, t), Offset((l + r) / 2f, b), Offset(l, (t + b) / 2f), Offset(r, (t + b) / 2f))
    for (p in mids) drawRect(Color.White, Offset(p.x - es / 2f, p.y - es / 2f), Size(es, es))
}
