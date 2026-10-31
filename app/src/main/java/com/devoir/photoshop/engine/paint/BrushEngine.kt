package com.devoir.photoshop.engine.paint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.devoir.photoshop.engine.history.PixelPatch
import java.util.BitSet
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.engine.layers.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** What the renderer needs to preview a stroke that has not been committed yet. */
class ActiveStroke(val layerId: String, val overlay: Bitmap, val mode: BrushMode, val opacity: Float)

/**
 * Round-brush / eraser engine.
 *
 * A stroke is drawn into a reusable same-size ARGB *overlay* (dabs accumulate there up to full
 * coverage). The overlay is shown over the layer at the stroke's opacity; on release only the dirty
 * rectangle is composited into the layer (SRC_OVER for paint, DST_OUT for erase) and cleared.
 * That gives correct per-stroke opacity, true alpha erasing, and no whole-bitmap copies per event.
 * All drawing is clipped to the document bounds. Main-thread only.
 */
class BrushEngine {
    private var overlayRes: BitmapResource? = null
    private var overlayCanvas: Canvas? = null

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val commitPaint = Paint()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private var target: BitmapResource? = null
    private var sampler: StrokeSampler? = null
    private var sx = 1f
    private var sy = 1f
    private var radius = 1f
    private var clipSave = -1
    private val dirty = RectF()
    private var hasDirty = false
    private var touched = BitSet()
    private var tileCols = 1
    private var tileRows = 1
    private var lastPatch: PixelPatch? = null

    /** True when the last committed stroke could not be backed up (out of memory). */
    var patchFailed: Boolean = false
        private set

    var active: ActiveStroke? = null
        private set
    val isActive: Boolean get() = active != null

    val overlayBytes: Long get() = overlayRes?.takeIf { !it.isReleased }?.byteCount ?: 0L

    /** Returns an error message, or null when the stroke started. [start] is in layer-local pixels. */
    fun begin(
        layerId: String, layer: Layer, target: BitmapResource, mode: BrushMode, settings: BrushSettings,
        docW: Int, docH: Int, start: Pt,
    ): String? {
        val bmp = target.bitmap
        val overlay = ensureOverlay(bmp.width, bmp.height) ?: return "Not enough memory to start painting."
        val canvas = overlayCanvas ?: return "Not enough memory to start painting."

        sx = bmp.width / layer.width
        sy = bmp.height / layer.height
        radius = max(0.5f, settings.size / 2f / layer.transform.avgScale * (sx + sy) / 2f)

        // Clip every dab to the document rectangle, expressed in this layer's pixel space.
        val corners = listOf(Pt(0f, 0f), Pt(docW.toFloat(), 0f), Pt(docW.toFloat(), docH.toFloat()), Pt(0f, docH.toFloat()))
            .map { LayerGeometry.docToLocal(layer, it.x, it.y) }
        val clip = Path().apply {
            moveTo(corners[0].x * sx, corners[0].y * sy)
            for (i in 1..3) lineTo(corners[i].x * sx, corners[i].y * sy)
            close()
        }
        clipSave = canvas.save()
        canvas.clipPath(clip)

        val rgb = (if (mode == BrushMode.Erase) 0xFF000000.toInt() else settings.color) or 0xFF000000.toInt()
        dabPaint.shader = null
        dabPaint.color = rgb
        if (settings.hardness < 0.99f) {
            val clear = rgb and 0x00FFFFFF
            dabPaint.shader = RadialGradient(
                0f, 0f, radius, intArrayOf(rgb, rgb, clear),
                floatArrayOf(0f, settings.hardness, 1f), Shader.TileMode.CLAMP,
            )
        }

        this.target = target
        hasDirty = false
        tileCols = (bmp.width + PixelPatch.TILE - 1) / PixelPatch.TILE
        tileRows = (bmp.height + PixelPatch.TILE - 1) / PixelPatch.TILE
        touched = BitSet(tileCols * tileRows)
        active = ActiveStroke(layerId, overlay, mode, settings.opacity)
        val spacing = max(0.5f, radius * 2f * SPACING_FRACTION)
        sampler = StrokeSampler(spacing) { x, y -> stamp(x, y) }.also { it.begin(start.x * sx, start.y * sy) }
        return null
    }

    fun move(local: Pt) { sampler?.add(local.x * sx, local.y * sy) }

    /** Commits the stroke and returns the pixels it replaced (null if it changed nothing or could not be backed up). */
    fun end(): PixelPatch? {
        sampler?.end()
        finish(commit = true)
        return lastPatch
    }

    fun cancel() = finish(commit = false)

    /** Frees the (large) overlay when no paint layer needs it any more. */
    fun releaseOverlay() {
        if (isActive) return
        overlayRes?.release()
        overlayRes = null
        overlayCanvas = null
    }

    private fun stamp(x: Float, y: Float) {
        val c = overlayCanvas ?: return
        c.save()
        c.translate(x, y)
        c.drawCircle(0f, 0f, radius, dabPaint)
        c.restore()
        val l = x - radius - 1f; val t = y - radius - 1f; val r = x + radius + 1f; val b = y + radius + 1f
        if (hasDirty) dirty.union(l, t, r, b) else { dirty.set(l, t, r, b); hasDirty = true }
        markTiles(l, t, r, b)
    }

    private fun markTiles(l: Float, t: Float, r: Float, b: Float) {
        val tile = PixelPatch.TILE
        val x0 = floor(l / tile).toInt().coerceAtLeast(0)
        val x1 = floor(r / tile).toInt().coerceAtMost(tileCols - 1)
        val y0 = floor(t / tile).toInt().coerceAtLeast(0)
        val y1 = floor(b / tile).toInt().coerceAtMost(tileRows - 1)
        for (ty in y0..y1) for (tx in x0..x1) touched.set(ty * tileCols + tx)
    }

    private fun finish(commit: Boolean) {
        lastPatch = null
        patchFailed = false
        val a = active
        val c = overlayCanvas
        val t = target
        if (c != null && clipSave >= 0) c.restoreToCount(clipSave)
        clipSave = -1
        if (a != null && c != null && hasDirty) {
            val w = a.overlay.width
            val h = a.overlay.height
            val r = Rect(
                floor(dirty.left).toInt().coerceIn(0, w), floor(dirty.top).toInt().coerceIn(0, h),
                ceil(dirty.right).toInt().coerceIn(0, w), ceil(dirty.bottom).toInt().coerceIn(0, h),
            )
            if (!r.isEmpty) {
                if (commit && t != null && !t.isReleased) {
                    // back up exactly the touched tiles BEFORE the stroke is composited into the layer
                    lastPatch = PixelPatch.capture(t, touched, tileCols)
                    patchFailed = lastPatch == null
                    commitPaint.alpha = (a.opacity * 255f).roundToInt().coerceIn(0, 255)
                    commitPaint.xfermode = if (a.mode == BrushMode.Erase) dstOut else null
                    Canvas(t.bitmap).drawBitmap(a.overlay, r, r, commitPaint)
                }
                c.drawRect(r, clearPaint) // reset only the touched region
            }
        }
        active = null
        target = null
        sampler = null
        hasDirty = false
    }

    private fun ensureOverlay(w: Int, h: Int): Bitmap? {
        val cur = overlayRes
        if (cur != null && !cur.isReleased && cur.bitmap.width == w && cur.bitmap.height == h) return cur.bitmap
        cur?.release()
        overlayRes = null
        overlayCanvas = null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlayRes = BitmapResource(bmp)
            overlayCanvas = Canvas(bmp)
            bmp
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private companion object { const val SPACING_FRACTION = 0.1f }
}
