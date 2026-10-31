package com.devoir.photoshop.engine.paint

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns raw touch points into evenly spaced dab positions along a smoothed curve
 * (quadratic Béziers through segment midpoints), so fast strokes leave no gaps and corners are soft.
 * Pure Kotlin: no Android dependencies.
 */
class StrokeSampler(private val spacing: Float, private val emit: (x: Float, y: Float) -> Unit) {
    init { require(spacing > 0f) { "spacing must be positive" } }

    private var curX = 0f
    private var curY = 0f
    private var sinceLast = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var midX = 0f
    private var midY = 0f
    private var started = false

    fun begin(x: Float, y: Float) {
        curX = x; curY = y; rawX = x; rawY = y; midX = x; midY = y
        sinceLast = 0f
        started = true
        emit(x, y)
    }

    fun add(x: Float, y: Float) {
        if (!started) return
        val nmx = (rawX + x) / 2f
        val nmy = (rawY + y) / 2f
        quad(midX, midY, rawX, rawY, nmx, nmy)
        midX = nmx; midY = nmy; rawX = x; rawY = y
    }

    /** Finishes the curve up to the last raw point. */
    fun end() {
        if (!started) return
        quad(midX, midY, rawX, rawY, rawX, rawY)
        started = false
    }

    private fun quad(ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float) {
        val len = hypot(cx - ax, cy - ay) + hypot(bx - cx, by - cy)
        if (len < 1e-4f) return
        val n = ceil(len / max(spacing * 0.5f, 0.25f)).toInt().coerceIn(1, MAX_STEPS)
        for (i in 1..n) {
            val t = i / n.toFloat()
            val u = 1f - t
            walkTo(u * u * ax + 2f * u * t * cx + t * t * bx, u * u * ay + 2f * u * t * cy + t * t * by)
        }
    }

    private fun walkTo(x: Float, y: Float) {
        val dx = x - curX
        val dy = y - curY
        val dist = hypot(dx, dy)
        if (dist < 1e-6f) return
        val ux = dx / dist
        val uy = dy / dist
        var remaining = dist
        while (sinceLast + remaining >= spacing) {
            val need = spacing - sinceLast
            curX += ux * need
            curY += uy * need
            emit(curX, curY)
            remaining -= need
            sinceLast = 0f
        }
        sinceLast += remaining
        curX = x
        curY = y
    }

    private companion object { const val MAX_STEPS = 4096 }
}
