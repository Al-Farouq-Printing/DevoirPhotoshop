package com.devoir.photoshop.engine.adjust

import kotlin.math.roundToInt

/**
 * Per-pixel work for the adjustments a colour matrix cannot express. Works on straight
 * (non-premultiplied) ARGB ints, exactly what Bitmap.getPixels/setPixels use. Alpha is never changed
 * and fully transparent pixels are never touched, so transparency is preserved.
 */
object PixelOps {
    /** Largest tonal shift at the extremes, as a fraction of full range. */
    const val TONAL_RANGE = 0.35f
    private val KERNEL = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1) // 3x3 gaussian-like, sums to 16

    /**
     * Signed shift (0..255 units) for each luma value. Shadows act mostly on dark pixels (weight (1-l)^2),
     * highlights mostly on bright ones (weight l^2), so neither is a global brightness change.
     */
    fun tonalLut(highlights: Int, shadows: Int): IntArray {
        val h = highlights.coerceIn(-100, 100) / 100f
        val s = shadows.coerceIn(-100, 100) / 100f
        return IntArray(256) { i ->
            val l = i / 255f
            ((s * (1f - l) * (1f - l) + h * l * l) * TONAL_RANGE * 255f).roundToInt()
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** In place. */
    fun applyTonal(buf: IntArray, count: Int, lut: IntArray) {
        for (i in 0 until count) {
            val p = buf[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val d = lut[(r * 77 + g * 150 + b * 29) shr 8]
            buf[i] = (a shl 24) or (clamp(r + d) shl 16) or (clamp(g + d) shl 8) or clamp(b + d)
        }
    }

    /**
     * Unsharp mask with a 3x3 blur: out = c + amount * (c - blur). [src] holds [srcRows] rows; rows
     * [outFirst, outFirst + outCount) are written to [dst]. Neighbours outside [src] are clamped to its
     * edge. The blur is alpha-weighted, so the colour of transparent neighbours never leaks in.
     */
    fun sharpenRows(src: IntArray, w: Int, srcRows: Int, outFirst: Int, outCount: Int, amount: Float, dst: IntArray) {
        for (oy in 0 until outCount) {
            val y = outFirst + oy
            val yu = if (y > 0) y - 1 else 0
            val yd = if (y < srcRows - 1) y + 1 else srcRows - 1
            for (x in 0 until w) {
                val c = src[y * w + x]
                val ca = c ushr 24
                if (ca == 0) { dst[oy * w + x] = c; continue }
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                for (ky in 0..2) {
                    val row = (if (ky == 0) yu else if (ky == 1) y else yd) * w
                    for (kx in 0..2) {
                        val p = src[row + (if (kx == 0) xl else if (kx == 1) x else xr)]
                        val pa = p ushr 24
                        if (pa == 0) continue
                        val wt = KERNEL[ky * 3 + kx] * pa
                        sa += wt
                        sr += wt * ((p shr 16) and 255)
                        sg += wt * ((p shr 8) and 255)
                        sb += wt * (p and 255)
                    }
                }
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val nr = clamp((r + amount * (r - sr / sa)).roundToInt())
                val ng = clamp((g + amount * (g - sg / sa)).roundToInt())
                val nb = clamp((b + amount * (b - sb / sa)).roundToInt())
                dst[oy * w + x] = (ca shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Sharpness 0..100 -> unsharp amount 0..2. */
    fun sharpAmount(sharpness: Int) = sharpness.coerceIn(0, 100) / 100f * 2f
}
