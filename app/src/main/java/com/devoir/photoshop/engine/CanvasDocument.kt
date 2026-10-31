package com.devoir.photoshop.engine

import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.model.ProjectConfig
import kotlin.math.min

const val BACKGROUND_ID = "background"

/** The required, always-locked bottom of the stack. Its fill comes from [ProjectConfig]. */
data class BackgroundLayer(val visible: Boolean = true)

/** Rectangle in document (canvas pixel) coordinates. */
data class ImagePlacement(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        /** Contain-fit, centred, never upscaled; aspect ratio is always preserved. */
        fun fit(docW: Float, docH: Float, imgW: Float, imgH: Float): ImagePlacement {
            val s = min(1f, min(docW / imgW, docH / imgH))
            val w = imgW * s
            val h = imgH * s
            return ImagePlacement((docW - w) / 2f, (docH - h) / 2f, w, h)
        }
    }
}

/**
 * The whole editable document: configuration, background, the layer stack (bottom to top) and the
 * selection. Immutable; every edit produces a new instance. Ready to be serialised in a later phase.
 * Viewport zoom/pan is view state and lives in the UI's ViewportController.
 */
data class CanvasDocument(
    val config: ProjectConfig,
    val background: BackgroundLayer = BackgroundLayer(),
    val layers: List<Layer> = emptyList(),
    val selectedLayerId: String = BACKGROUND_ID,
) {
    val width: Int get() = config.widthPx
    val height: Int get() = config.heightPx

    fun layer(id: String?): Layer? = layers.firstOrNull { it.id == id }
    val selectedLayer: Layer? get() = layer(selectedLayerId)
}
