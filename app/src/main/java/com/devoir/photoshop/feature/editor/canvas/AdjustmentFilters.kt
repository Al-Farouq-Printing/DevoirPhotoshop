package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import com.devoir.photoshop.engine.adjust.AdjustmentMatrix
import com.devoir.photoshop.engine.adjust.AdjustmentState

/** The colour stage as a GPU colour filter, or null when nothing needs to be applied. */
fun AdjustmentState.toColorFilter(): ColorFilter? {
    if (isColorIdentity) return null
    return ColorFilter.colorMatrix(ColorMatrix(AdjustmentMatrix.build(this)))
}

/** Small cache so a filter object is not rebuilt on every frame. Tonal fields do not affect the key. */
class AdjustmentFilterCache {
    private val map = HashMap<AdjustmentState, ColorFilter?>()
    fun get(state: AdjustmentState): ColorFilter? {
        val key = state.colorOnly()
        if (map.size > 64) map.clear()
        return map.getOrPut(key) { key.toColorFilter() }
    }
}

/** What the renderer needs to draw adjusted image layers. */
class AdjustRenderContext(
    val filterFor: (AdjustmentState) -> ColorFilter?,
    /** Result of the pixel stage for a layer id, if one is ready. */
    val processedFor: (String) -> ImageBitmap?,
)
