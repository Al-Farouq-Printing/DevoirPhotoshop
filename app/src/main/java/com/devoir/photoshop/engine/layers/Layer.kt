package com.devoir.photoshop.engine.layers

import android.graphics.Bitmap
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.model.ImportedImageInfo

/** Text, Shape and Adjustment are reserved: they have no content class or renderer yet. */
enum class LayerType(val label: String) {
    Image("Image"), Paint("Paint"), Text("Text"), Shape("Shape"), Adjustment("Adjustment"),
}

/** Only Normal is rendered. Add entries here (and map them in the renderer) in a later phase. */
enum class BlendMode(val label: String) { Normal("Normal") }

enum class MoveDirection { Up, Down, Top, Bottom }

/**
 * Non-destructive layer transform. x/y = layer centre in document pixels; rotation in degrees
 * (clockwise, [0, 360)). Flips mirror the content in the layer's own frame; the pixels are never changed.
 * Render order: flip, scale, rotate, translate.
 */
data class LayerTransform(
    val x: Float,
    val y: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val avgScale: Float get() = (scaleX + scaleY) / 2f
}

sealed interface LayerContent {
    val resource: BitmapResource?

    /** Bumped when pixels change, so UI (thumbnails) knows to refresh. */
    val revision: Int get() = 0

    /** Content for a duplicated layer. Image pixels are immutable and shared; paint pixels are copied. */
    fun duplicate(): LayerContent
    fun release()

    /**
     * [adjustments] is a plain value applied on top of the untouched pixels in [resource].
     * Duplicating the layer shares the (immutable) pixels and copies the value, so the two layers
     * can be adjusted independently.
     */
    data class Image(
        override val resource: BitmapResource,
        val info: ImportedImageInfo,
        val adjustments: AdjustmentState = AdjustmentState(),
    ) : LayerContent {
        override fun duplicate() = copy() // same immutable pixels; the history ledger tracks who holds them
        override fun release() = resource.release()
    }

    /** Pixels are allocated lazily on the first stroke; an empty paint layer costs no bitmap memory. */
    data class Paint(override val resource: BitmapResource? = null, override val revision: Int = 0) : LayerContent {
        override fun duplicate(): LayerContent {
            val src = resource ?: return copy(revision = 0)
            val pixels = src.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw OutOfMemoryError("Couldn't copy paint layer")
            return Paint(BitmapResource(pixels), 0)
        }
        override fun release() { resource?.release() }
    }

    /** Editable text. No pixels: the renderer lays it out from [spec]. */
    data class Text(val spec: TextSpec) : LayerContent {
        override val resource: BitmapResource? get() = null
        override fun duplicate() = copy() // immutable data: a copy can never affect the original
        override fun release() {}
    }

    /** Procedural vector shape. No pixels. */
    data class Shape(val spec: ShapeSpec) : LayerContent {
        override val resource: BitmapResource? get() = null
        override fun duplicate() = copy()
        override fun release() {}
    }
}

/** Z-order is the layer's index in [CanvasDocument.layers] (0 = bottom). */
data class Layer(
    val id: String,
    val name: String,
    val type: LayerType,
    val content: LayerContent,
    /** Unscaled size in document pixels. */
    val width: Float,
    val height: Float,
    val transform: LayerTransform,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val locked: Boolean = false,
    val blendMode: BlendMode = BlendMode.Normal,
    /** What "Reset transform" restores. Follows the layer when the canvas is cropped/resized. */
    val defaultTransform: LayerTransform = transform,
) {
    val isEditable: Boolean get() = !locked
}
