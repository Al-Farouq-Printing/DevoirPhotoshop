package com.devoir.photoshop.engine.history

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.devoir.photoshop.engine.layers.BitmapResource
import java.util.BitSet
import kotlin.math.min

/**
 * Pixel backup for one stroke: only the 64x64 tiles the stroke touched, copied before the stroke was
 * composited. Memory grows with the touched area, not with the layer size, and a long thin stroke on a
 * big layer stays small. The patch is *swapped*: undo puts the saved tiles back and keeps what it
 * replaced, so redo can swap again. Only one set of tiles exists at a time. Tiles are copied and
 * restored with exact (premultiplied) pixel copies, so repeated undo/redo never drifts.
 */
class PixelPatch private constructor(
    val resource: BitmapResource,
    private val tiles: MutableList<Tile>,
    val bytes: Long,
) {
    class Tile(val x: Int, val y: Int, val w: Int, val h: Int, var bitmap: Bitmap)

    class HistoryException(message: String) : Exception(message)

    /** Atomic: every replacement tile is allocated first, and nothing is modified unless all succeeded. */
    fun swap() {
        if (resource.isReleased || resource.bitmap.isRecycled) throw HistoryException("The layer's pixels are no longer available")
        val target = resource.bitmap
        val current = ArrayList<Bitmap>(tiles.size)
        try {
            for (t in tiles) current.add(Bitmap.createBitmap(target, t.x, t.y, t.w, t.h))
        } catch (e: Throwable) {
            current.forEach { it.recycle() }
            throw HistoryException("Not enough memory to restore the pixels")
        }
        val canvas = Canvas(target)
        for ((i, t) in tiles.withIndex()) {
            canvas.drawBitmap(t.bitmap, t.x.toFloat(), t.y.toFloat(), SRC)
            t.bitmap.recycle()
            t.bitmap = current[i]
        }
    }

    fun release() {
        tiles.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        tiles.clear()
    }

    companion object {
        const val TILE = 64
        private val SRC = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        /** Copies the touched tiles of [res] as they are right now. Returns null if memory runs out. */
        fun capture(res: BitmapResource, touched: BitSet, cols: Int): PixelPatch? {
            if (res.isReleased || res.bitmap.isRecycled) return null
            val bmp = res.bitmap
            val tiles = ArrayList<Tile>()
            var bytes = 0L
            try {
                var i = touched.nextSetBit(0)
                while (i >= 0) {
                    val x = (i % cols) * TILE
                    val y = (i / cols) * TILE
                    val w = min(TILE, bmp.width - x)
                    val h = min(TILE, bmp.height - y)
                    if (w > 0 && h > 0) {
                        val copy = Bitmap.createBitmap(bmp, x, y, w, h)
                        tiles.add(Tile(x, y, w, h, copy))
                        bytes += copy.allocationByteCount
                    }
                    i = touched.nextSetBit(i + 1)
                }
            } catch (e: Throwable) {
                tiles.forEach { it.bitmap.recycle() }
                return null
            }
            return PixelPatch(res, tiles, bytes)
        }
    }
}
