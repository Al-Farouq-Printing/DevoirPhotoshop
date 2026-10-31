package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ViewportTarget(val scale: Float, val offset: Offset, val fitted: Boolean)

/**
 * Zoom + pan state. [scale] = screen pixels per document pixel (1.0 == 100%).
 * [offset] = screen position of the document's top-left corner.
 */
@Stable
class ViewportController {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var isFitted by mutableStateOf(true)
        private set

    private var viewSize = Size.Zero
    private var docSize = Size.Zero
    private var fitPadding = 0f

    val zoomPercent: Int get() = (scale * 100f).roundToInt()

    val fitScale: Float
        get() {
            if (viewSize.width <= 0f || docSize.width <= 0f || docSize.height <= 0f) return 1f
            val aw = (viewSize.width - 2 * fitPadding).coerceAtLeast(1f)
            val ah = (viewSize.height - 2 * fitPadding).coerceAtLeast(1f)
            return min(aw / docSize.width, ah / docSize.height)
        }

    private val minScale get() = fitScale * 0.5f
    private val maxScale get() = max(32f, fitScale * 4f)

    fun setDocSize(size: Size) {
        if (size == docSize) return
        docSize = size
        isFitted = true
        relayout()
    }

    fun setViewSize(size: Size, fitPaddingPx: Float) {
        fitPadding = fitPaddingPx
        if (size == viewSize) return
        viewSize = size
        relayout()
    }

    private fun relayout() {
        if (viewSize.width <= 0f || docSize.width <= 0f) return
        if (isFitted) {
            scale = fitScale
            offset = centered(scale)
        } else {
            scale = scale.coerceIn(minScale, maxScale)
            offset = clampOffset(offset, scale)
        }
    }

    private fun centered(s: Float) = Offset(
        (viewSize.width - docSize.width * s) / 2f,
        (viewSize.height - docSize.height * s) / 2f,
    )

    private fun clampAxis(o: Float, view: Float, content: Float): Float =
        if (content <= view) (view - content) / 2f // smaller than the view: stay centred
        else {
            val slack = view * 0.15f // allow a little overscroll so edges are reachable
            o.coerceIn(view - content - slack, slack)
        }

    private fun clampOffset(o: Offset, s: Float) = Offset(
        clampAxis(o.x, viewSize.width, docSize.width * s),
        clampAxis(o.y, viewSize.height, docSize.height * s),
    )

    /** Live gesture: focal-point zoom plus pan. */
    fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        isFitted = false
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val ratio = newScale / scale
        scale = newScale
        offset = clampOffset(centroid - (centroid - offset) * ratio + pan, newScale)
    }

    fun fitTarget() = ViewportTarget(fitScale, centered(fitScale), fitted = true)

    private fun targetAround(rawScale: Float, focal: Offset): ViewportTarget {
        val s = rawScale.coerceIn(minScale, maxScale)
        val o = clampOffset(focal - (focal - offset) * (s / scale), s)
        return ViewportTarget(s, o, fitted = false)
    }

    fun doubleTapTarget(focal: Offset): ViewportTarget =
        if (scale > fitScale * 1.15f) fitTarget() else targetAround(max(1f, fitScale * 2.5f), focal)

    fun stepTarget(factor: Float): ViewportTarget =
        targetAround(scale * factor, Offset(viewSize.width / 2f, viewSize.height / 2f))

    suspend fun animateTo(t: ViewportTarget) {
        val s0 = scale
        val o0 = offset
        isFitted = false
        Animatable(0f).animateTo(1f, tween(220)) {
            scale = s0 + (t.scale - s0) * value
            offset = Offset(o0.x + (t.offset.x - o0.x) * value, o0.y + (t.offset.y - o0.y) * value)
        }
        scale = t.scale
        offset = t.offset
        isFitted = t.fitted
    }
}
