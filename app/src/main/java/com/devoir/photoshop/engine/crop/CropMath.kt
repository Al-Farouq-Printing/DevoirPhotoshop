package com.devoir.photoshop.engine.crop

import com.devoir.photoshop.engine.paint.Pt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

enum class CropHandle { Move, Left, Right, Top, Bottom, TopLeft, TopRight, BottomLeft, BottomRight }

enum class CropAspectOption(val label: String) {
    Free("Free"), Square("1:1"), R4x3("4:3"), R3x4("3:4"), R16x9("16:9"), R9x16("9:16"), Original("Original"), Custom("Custom"),
}

/** Pure crop-rectangle maths, all in document pixels and always kept inside the document. */
object CropMath {
    const val MIN_SIZE = 8f

    fun full(w: Int, h: Int) = CropRect(0f, 0f, w.toFloat(), h.toFloat())

    /** width / height, or null for a free crop. */
    fun ratioOf(option: CropAspectOption, docW: Int, docH: Int, custom: Float): Float? = when (option) {
        CropAspectOption.Free -> null
        CropAspectOption.Square -> 1f
        CropAspectOption.R4x3 -> 4f / 3f
        CropAspectOption.R3x4 -> 3f / 4f
        CropAspectOption.R16x9 -> 16f / 9f
        CropAspectOption.R9x16 -> 9f / 16f
        CropAspectOption.Original -> docW.toFloat() / docH
        CropAspectOption.Custom -> custom.takeIf { it > 0f && it.isFinite() }
    }

    private fun minSize(docW: Int, docH: Int) = min(MIN_SIZE, min(docW, docH).toFloat())

    /** Largest rectangle of [ratio] that fits inside [rect], centred on it. */
    fun fitAspect(rect: CropRect, ratio: Float, docW: Int, docH: Int): CropRect {
        var w = rect.width
        var h = w / ratio
        if (h > rect.height) { h = rect.height; w = h * ratio }
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val l = (cx - w / 2f).coerceIn(0f, max(0f, docW - w))
        val t = (cy - h / 2f).coerceIn(0f, max(0f, docH - h))
        return CropRect(l, t, l + w, t + h)
    }

    fun move(r: CropRect, dx: Float, dy: Float, docW: Int, docH: Int): CropRect {
        val l = (r.left + dx).coerceIn(0f, max(0f, docW - r.width))
        val t = (r.top + dy).coerceIn(0f, max(0f, docH - r.height))
        return CropRect(l, t, l + r.width, t + r.height)
    }

    private fun isLeft(h: CropHandle) = h == CropHandle.Left || h == CropHandle.TopLeft || h == CropHandle.BottomLeft
    private fun isRight(h: CropHandle) = h == CropHandle.Right || h == CropHandle.TopRight || h == CropHandle.BottomRight
    private fun isTop(h: CropHandle) = h == CropHandle.Top || h == CropHandle.TopLeft || h == CropHandle.TopRight
    private fun isBottom(h: CropHandle) = h == CropHandle.Bottom || h == CropHandle.BottomLeft || h == CropHandle.BottomRight

    /** [dx]/[dy] = finger movement since touch-down; [r0] = rectangle at touch-down. */
    fun resize(r0: CropRect, handle: CropHandle, dx: Float, dy: Float, docW: Int, docH: Int, ratio: Float?): CropRect {
        if (handle == CropHandle.Move) return move(r0, dx, dy, docW, docH)
        val min = minSize(docW, docH)
        if (ratio == null) {
            var l = r0.left; var t = r0.top; var r = r0.right; var b = r0.bottom
            if (isLeft(handle)) l = (r0.left + dx).coerceIn(0f, r0.right - min)
            if (isRight(handle)) r = (r0.right + dx).coerceIn(r0.left + min, docW.toFloat())
            if (isTop(handle)) t = (r0.top + dy).coerceIn(0f, r0.bottom - min)
            if (isBottom(handle)) b = (r0.bottom + dy).coerceIn(r0.top + min, docH.toFloat())
            return CropRect(l, t, r, b)
        }
        val isCorner = (isLeft(handle) || isRight(handle)) && (isTop(handle) || isBottom(handle))
        return if (isCorner) cornerWithRatio(r0, handle, dx, dy, docW, docH, ratio, min)
        else edgeWithRatio(r0, handle, dx, dy, docW, docH, ratio, min)
    }

    private fun cornerWithRatio(r0: CropRect, hd: CropHandle, dx: Float, dy: Float, docW: Int, docH: Int, ratio: Float, min: Float): CropRect {
        val dirX = if (isLeft(hd)) -1f else 1f
        val dirY = if (isTop(hd)) -1f else 1f
        val ax = if (isLeft(hd)) r0.right else r0.left
        val ay = if (isTop(hd)) r0.bottom else r0.top
        val px = (if (isLeft(hd)) r0.left else r0.right) + dx
        val py = (if (isTop(hd)) r0.top else r0.bottom) + dy
        val w0 = ((px - ax) * dirX).coerceAtLeast(min)
        val h0 = ((py - ay) * dirY).coerceAtLeast(min)
        var w = (w0 + h0 * ratio) / 2f
        val maxW = if (dirX > 0) docW - ax else ax
        val maxH = if (dirY > 0) docH - ay else ay
        w = min(w, min(maxW, maxH * ratio)).coerceAtLeast(1f)
        val h = w / ratio
        val x2 = ax + dirX * w
        val y2 = ay + dirY * h
        return CropRect(min(ax, x2), min(ay, y2), max(ax, x2), max(ay, y2))
    }

    private fun edgeWithRatio(r0: CropRect, hd: CropHandle, dx: Float, dy: Float, docW: Int, docH: Int, ratio: Float, min: Float): CropRect {
        val horizontal = isLeft(hd) || isRight(hd)
        if (horizontal) {
            val dir = if (isLeft(hd)) -1f else 1f
            val anchor = if (isLeft(hd)) r0.right else r0.left
            val edge = (if (isLeft(hd)) r0.left else r0.right) + dx
            var w = ((edge - anchor) * dir).coerceAtLeast(min)
            val maxW = if (dir > 0) docW - anchor else anchor
            w = min(w, min(maxW, docH * ratio)).coerceAtLeast(1f)
            val h = w / ratio
            val cy = (r0.top + r0.bottom) / 2f
            val t = (cy - h / 2f).coerceIn(0f, max(0f, docH - h))
            val x2 = anchor + dir * w
            return CropRect(min(anchor, x2), t, max(anchor, x2), t + h)
        }
        val dir = if (isTop(hd)) -1f else 1f
        val anchor = if (isTop(hd)) r0.bottom else r0.top
        val edge = (if (isTop(hd)) r0.top else r0.bottom) + dy
        var h = ((edge - anchor) * dir).coerceAtLeast(min)
        val maxH = if (dir > 0) docH - anchor else anchor
        h = min(h, min(maxH, docW / ratio)).coerceAtLeast(1f)
        val w = h * ratio
        val cx = (r0.left + r0.right) / 2f
        val l = (cx - w / 2f).coerceIn(0f, max(0f, docW - w))
        val y2 = anchor + dir * h
        return CropRect(l, min(anchor, y2), l + w, max(anchor, y2))
    }

    /** Corners, then edges, then the body. [radius] in document pixels. */
    fun hitTest(r: CropRect, p: Pt, radius: Float): CropHandle? {
        fun near(x: Float, y: Float) = kotlin.math.hypot(p.x - x, p.y - y) <= radius
        when {
            near(r.left, r.top) -> return CropHandle.TopLeft
            near(r.right, r.top) -> return CropHandle.TopRight
            near(r.left, r.bottom) -> return CropHandle.BottomLeft
            near(r.right, r.bottom) -> return CropHandle.BottomRight
        }
        val inX = p.x >= r.left - radius && p.x <= r.right + radius
        val inY = p.y >= r.top - radius && p.y <= r.bottom + radius
        if (inY && abs(p.x - r.left) <= radius) return CropHandle.Left
        if (inY && abs(p.x - r.right) <= radius) return CropHandle.Right
        if (inX && abs(p.y - r.top) <= radius) return CropHandle.Top
        if (inX && abs(p.y - r.bottom) <= radius) return CropHandle.Bottom
        return if (p.x in r.left..r.right && p.y in r.top..r.bottom) CropHandle.Move else null
    }
}
