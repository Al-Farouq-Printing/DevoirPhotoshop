package com.devoir.photoshop.engine.paint

import android.graphics.Bitmap
import com.devoir.photoshop.engine.layers.BitmapResource
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SurfaceSize(val width: Int, val height: Int)

/** Allocates a paint layer's pixel buffer, shrinking it on very large documents instead of failing. */
object PaintSurfaceFactory {
    /** Per-surface cap. Keeps the per-frame texture upload during a stroke manageable. */
    const val MAX_PAINT_PIXELS = 6_000_000L
    const val MIN_PAINT_PIXELS = 65_536L

    sealed interface Result {
        class Created(val resource: BitmapResource, val reduced: Boolean) : Result
        class Failed(val message: String) : Result
    }

    /** Pure sizing rule. Budget is divided by 8 because the stroke overlay needs a same-size buffer. */
    fun planSize(layerW: Int, layerH: Int, availableBytes: Long): SurfaceSize? {
        val pixels = layerW.toLong() * layerH
        val maxPixels = min(MAX_PAINT_PIXELS, availableBytes / 8)
        if (maxPixels < MIN_PAINT_PIXELS) return null
        if (pixels <= maxPixels) return SurfaceSize(layerW, layerH)
        val f = sqrt(maxPixels.toDouble() / pixels)
        return SurfaceSize(max(1, floor(layerW * f).toInt()), max(1, floor(layerH * f).toInt()))
    }

    fun create(layerW: Int, layerH: Int, availableBytes: Long): Result {
        val size = planSize(layerW, layerH, availableBytes)
            ?: return Result.Failed("Not enough free memory to paint. Delete or hide a large layer first.")
        return try {
            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            Result.Created(BitmapResource(bmp), reduced = size.width < layerW || size.height < layerH)
        } catch (e: OutOfMemoryError) {
            Result.Failed("Not enough memory to create a paint layer for this canvas.")
        }
    }
}
