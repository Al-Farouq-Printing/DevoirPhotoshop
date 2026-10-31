package com.devoir.photoshop.engine.transform

import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.paint.Pt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TransformHandle { Move, Rotate, TopLeft, TopRight, BottomRight, BottomLeft }

object TransformMath {
    const val MIN_SCALE = 0.02f
    const val MAX_SCALE = 8f
    private const val SNAP_STEP = 45f
    private const val SNAP_TOLERANCE = 2.5f

    /** Any angle -> [0, 360). */
    fun normalizeDegrees(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        val r = ((deg % 360f) + 360f) % 360f
        return if (r >= 360f) 0f else r
    }

    /** Snaps to multiples of 45° when within 2.5°, so 0/45/90/180/270 are easy to hit. */
    fun snapAngle(deg: Float): Float {
        val n = normalizeDegrees(deg)
        val nearest = (n / SNAP_STEP).roundToInt() * SNAP_STEP
        return if (abs(n - nearest) <= SNAP_TOLERANCE) normalizeDegrees(nearest) else n
    }
}

/**
 * Box frame = the layer's rotated frame with origin at the layer centre, extents = displayed size
 * (base size x scale). Flips only mirror the content, so the box itself is unaffected by them.
 */
object TransformGeometry {
    fun halfSize(layer: Layer) = Pt(layer.width * layer.transform.scaleX / 2f, layer.height * layer.transform.scaleY / 2f)

    fun boxToDoc(layer: Layer, u: Float, v: Float): Pt {
        val t = layer.transform
        val r = Math.toRadians(t.rotation.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Pt(t.x + u * c - v * s, t.y + u * s + v * c)
    }

    fun docToBox(layer: Layer, p: Pt): Pt {
        val t = layer.transform
        val r = Math.toRadians(t.rotation.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val dx = p.x - t.x; val dy = p.y - t.y
        return Pt(dx * c + dy * s, -dx * s + dy * c)
    }

    /** TL, TR, BR, BL in document coordinates. */
    fun corners(layer: Layer): List<Pt> {
        val h = halfSize(layer)
        return listOf(boxToDoc(layer, -h.x, -h.y), boxToDoc(layer, h.x, -h.y), boxToDoc(layer, h.x, h.y), boxToDoc(layer, -h.x, h.y))
    }

    fun rotationHandle(layer: Layer, offsetDoc: Float): Pt = boxToDoc(layer, 0f, -halfSize(layer).y - offsetDoc)

    private fun dist(a: Pt, b: Pt) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    /** Rotation handle first, then corners, then the body. Radii are in document pixels. */
    fun hitTest(layer: Layer, p: Pt, radiusDoc: Float, rotOffsetDoc: Float): TransformHandle? {
        if (dist(p, rotationHandle(layer, rotOffsetDoc)) <= radiusDoc) return TransformHandle.Rotate
        val corners = corners(layer)
        val order = listOf(TransformHandle.TopLeft, TransformHandle.TopRight, TransformHandle.BottomRight, TransformHandle.BottomLeft)
        var best: TransformHandle? = null
        var bestD = radiusDoc
        for (i in 0..3) {
            val d = dist(p, corners[i])
            if (d <= bestD) { bestD = d; best = order[i] }
        }
        if (best != null) return best
        val b = docToBox(layer, p)
        val h = halfSize(layer)
        return if (abs(b.x) <= h.x && abs(b.y) <= h.y) TransformHandle.Move else null
    }
}

/** One drag. Everything is computed from the state at touch-down, so there is no drift. */
class TransformSession(
    private val start: LayerTransform,
    private val baseW: Float,
    private val baseH: Float,
    private val handle: TransformHandle,
    private val startPoint: Pt,
    private val lockRatio: Boolean,
) {
    fun update(p: Pt): LayerTransform = when (handle) {
        TransformHandle.Move -> start.copy(x = start.x + p.x - startPoint.x, y = start.y + p.y - startPoint.y)
        TransformHandle.Rotate -> rotate(p)
        else -> scale(p)
    }

    private fun rotate(p: Pt): LayerTransform {
        val a0 = atan2(startPoint.y - start.y, startPoint.x - start.x)
        val a1 = atan2(p.y - start.y, p.x - start.x)
        val deg = start.rotation + Math.toDegrees((a1 - a0).toDouble()).toFloat()
        return start.copy(rotation = TransformMath.snapAngle(deg))
    }

    private fun scale(p: Pt): LayerTransform {
        if (baseW <= 0f || baseH <= 0f) return start
        val cx = if (handle == TransformHandle.TopLeft || handle == TransformHandle.BottomLeft) -1f else 1f
        val cy = if (handle == TransformHandle.TopLeft || handle == TransformHandle.TopRight) -1f else 1f
        val hw0 = baseW * start.scaleX / 2f
        val hh0 = baseH * start.scaleY / 2f
        val r = Math.toRadians(start.rotation.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val dx = p.x - startPoint.x; val dy = p.y - startPoint.y
        val bx = dx * c + dy * s
        val by = -dx * s + dy * c
        // vector from the fixed (opposite) corner to the dragged corner, in the box frame
        val newW = (2f * cx * hw0 + bx) * cx
        val newH = (2f * cy * hh0 + by) * cy

        val sx: Float
        val sy: Float
        if (lockRatio) {
            var f = (newW / (2f * hw0) + newH / (2f * hh0)) / 2f
            val lo = max(TransformMath.MIN_SCALE / start.scaleX, TransformMath.MIN_SCALE / start.scaleY)
            val hi = min(TransformMath.MAX_SCALE / start.scaleX, TransformMath.MAX_SCALE / start.scaleY)
            f = f.coerceIn(lo, max(lo, hi))
            sx = start.scaleX * f; sy = start.scaleY * f
        } else {
            sx = (newW / baseW).coerceIn(TransformMath.MIN_SCALE, TransformMath.MAX_SCALE)
            sy = (newH / baseH).coerceIn(TransformMath.MIN_SCALE, TransformMath.MAX_SCALE)
        }
        val w = baseW * sx
        val h = baseH * sy
        // keep the opposite corner fixed in the document
        val ax = start.x + (-cx * hw0) * c - (-cy * hh0) * s
        val ay = start.y + (-cx * hw0) * s + (-cy * hh0) * c
        val ncx = ax + (cx * w / 2f) * c - (cy * h / 2f) * s
        val ncy = ay + (cx * w / 2f) * s + (cy * h / 2f) * c
        return start.copy(x = ncx, y = ncy, scaleX = sx, scaleY = sy)
    }
}
