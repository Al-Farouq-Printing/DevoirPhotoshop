package com.devoir.photoshop.engine.layers

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/**
 * Reference-counted pixel storage. Duplicated layers share one resource instead of copying
 * full-resolution pixels. The last release marks it released at once (the renderer then skips it)
 * and recycles the bitmaps after a short delay so an in-flight frame can never draw a recycled bitmap.
 * Main-thread only.
 */
class BitmapResource(val bitmap: Bitmap, val thumbnail: Bitmap? = null) {
    private var refs = 1

    var isReleased: Boolean = false
        private set

    val byteCount: Long = bitmap.allocationByteCount.toLong()

    /** True when more than one layer uses these pixels: future pixel edits must copy-on-write first. */
    val isShared: Boolean get() = refs > 1

    fun retain(): BitmapResource {
        check(!isReleased) { "Resource already released" }
        refs++
        return this
    }

    fun release() {
        if (isReleased) return
        if (--refs > 0) return
        isReleased = true
        val b = bitmap
        val t = thumbnail
        Handler(Looper.getMainLooper()).postDelayed({
            if (!b.isRecycled) b.recycle()
            if (t != null && !t.isRecycled) t.recycle()
        }, RECYCLE_DELAY_MS)
    }

    private companion object {
        const val RECYCLE_DELAY_MS = 1000L
    }
}
