package com.devoir.photoshop.engine.paint

import com.devoir.photoshop.engine.layers.Layer
import kotlin.math.cos
import kotlin.math.sin

data class Pt(val x: Float, val y: Float)

/** Coordinate mapping. Must stay the exact inverse of the renderer's layer transform. */
object LayerGeometry {
    /**
     * Document pixels -> layer-local pixels (unscaled layer size, origin at layer top-left).
     * Renderer order is: translate(centre) · rotate · scale(flip) · translate(-size/2); this inverts it.
     */
    fun docToLocal(layer: Layer, x: Float, y: Float): Pt {
        val t = layer.transform
        val rad = Math.toRadians(t.rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - t.x
        val dy = y - t.y
        val rx = dx * c + dy * s
        val ry = -dx * s + dy * c
        var lx = rx / t.scaleX
        var ly = ry / t.scaleY
        if (t.flipH) lx = -lx
        if (t.flipV) ly = -ly
        return Pt(lx + layer.width / 2f, ly + layer.height / 2f)
    }
}
