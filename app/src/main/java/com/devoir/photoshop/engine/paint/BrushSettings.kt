package com.devoir.photoshop.engine.paint

import kotlin.math.ln
import kotlin.math.pow

enum class BrushMode { Paint, Erase }

/** Size is in document pixels (independent of zoom). Opacity is per stroke, not per layer. */
data class BrushSettings(
    val size: Float = 20f,
    val opacity: Float = 1f,
    val hardness: Float = 1f,
    val color: Int = 0xFF000000.toInt(),
) {
    fun sanitized() = copy(
        size = size.coerceIn(MIN_SIZE, MAX_SIZE),
        opacity = opacity.coerceIn(0f, 1f),
        hardness = hardness.coerceIn(0f, 1f),
        color = color or OPAQUE, // alpha is expressed through [opacity]
    )

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 2000f
        private val OPAQUE = 0xFF000000.toInt()

        /** Logarithmic slider mapping so small sizes stay easy to hit. */
        fun sizeToSlider(size: Float): Float =
            ln(size.coerceIn(MIN_SIZE, MAX_SIZE) / MIN_SIZE) / ln(MAX_SIZE / MIN_SIZE)

        fun sliderToSize(t: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(t.coerceIn(0f, 1f))
    }
}
