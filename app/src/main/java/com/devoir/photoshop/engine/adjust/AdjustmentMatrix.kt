package com.devoir.photoshop.engine.adjust

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds one Android-style 4x5 colour matrix (row-major, RGBA in/out, translation column in 0..255)
 * for the colour stage of the pipeline. The alpha row is always identity, so transparency is preserved.
 * The matrix runs on the GPU as a ColorFilter: no bitmap is created or modified.
 */
object AdjustmentMatrix {
    private const val LUM_R = 0.299f
    private const val LUM_G = 0.587f
    private const val LUM_B = 0.114f

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** The matrix that applies [a] first and then [b]. */
    fun then(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (i in 0..3) for (j in 0..4) {
            var s = 0f
            for (k in 0..3) s += b[i * 5 + k] * a[k * 5 + j]
            if (j == 4) s += b[i * 5 + 4]
            r[i * 5 + j] = s
        }
        return r
    }

    fun isIdentity(m: FloatArray): Boolean {
        val id = identity()
        for (i in 0..19) if (kotlin.math.abs(m[i] - id[i]) > 1e-4f) return false
        return true
    }

    private fun scale(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun translate(t: Float) = floatArrayOf(
        1f, 0f, 0f, 0f, t,
        0f, 1f, 0f, 0f, t,
        0f, 0f, 1f, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Contrast about mid-grey: out = (in - 127.5) * f + 127.5. */
    private fun contrastMatrix(f: Float): FloatArray {
        val t = 127.5f * (1f - f)
        return floatArrayOf(
            f, 0f, 0f, 0f, t,
            0f, f, 0f, 0f, t,
            0f, 0f, f, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Luma-preserving saturation: 0 = grey, 1 = unchanged, 2 = double. */
    private fun saturationMatrix(s: Float): FloatArray {
        val i = 1f - s
        return floatArrayOf(
            LUM_R * i + s, LUM_G * i, LUM_B * i, 0f, 0f,
            LUM_R * i, LUM_G * i + s, LUM_B * i, 0f, 0f,
            LUM_R * i, LUM_G * i, LUM_B * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Rotation of colours around the grey axis (luma-preserving). */
    private fun hueMatrix(degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return floatArrayOf(
            lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
            lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun filterMatrix(f: FilterPreset): FloatArray = when (f) {
        FilterPreset.Original -> identity()
        FilterPreset.Grayscale -> saturationMatrix(0f)
        FilterPreset.Sepia -> floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        FilterPreset.Invert -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        FilterPreset.HighContrast -> then(contrastMatrix(1.7f), saturationMatrix(1.15f))
        FilterPreset.Cool -> then(scale(0.9f, 1f, 1.1f), saturationMatrix(1.05f))
        FilterPreset.Warm -> scale(1.1f, 1.02f, 0.88f)
        FilterPreset.Vintage -> then(
            then(saturationMatrix(0.7f), scale(1.08f, 1f, 0.82f)),
            then(contrastMatrix(0.88f), translate(14f)), // faded: lifted blacks, softer contrast
        )
        FilterPreset.BlackWhite -> then(saturationMatrix(0f), contrastMatrix(1.35f))
    }

    /**
     * Exposure is a gain of 2^stops in sRGB values (+-2 stops at +-100). This is an approximation of
     * true linear-light exposure, which would need a gamma round-trip per pixel.
     */
    private fun exposureMatrix(e: Int): FloatArray {
        val g = 2f.pow(e / 100f * 2f)
        return scale(g, g, g)
    }

    /** Warm = more red / less blue (and the reverse for cool). */
    private fun temperatureMatrix(t: Int): FloatArray {
        val k = t / 100f * 0.25f
        return scale(1f + k, 1f, 1f - k)
    }

    /** Magenta (+) lowers green and nudges red/blue up; green (-) does the opposite. */
    private fun tintMatrix(t: Int): FloatArray {
        val k = t / 100f * 0.2f
        return scale(1f + k / 2f, 1f - k, 1f + k / 2f)
    }

    private fun brightnessMatrix(b: Int) = translate(b / 100f * 0.4f * 255f)

    private fun contrastForValue(c: Int): FloatArray {
        val f = if (c >= 0) 1f + c / 100f * 1.5f else 1f + c / 100f * 0.9f
        return contrastMatrix(f)
    }

    private fun saturationForValue(s: Int) = saturationMatrix(1f + s / 100f)

    /** The whole colour stage, in the documented order. */
    fun build(state: AdjustmentState): FloatArray {
        var m = filterMatrix(state.filter)
        if (state.exposure != 0) m = then(m, exposureMatrix(state.exposure))
        if (state.temperature != 0) m = then(m, temperatureMatrix(state.temperature))
        if (state.tint != 0) m = then(m, tintMatrix(state.tint))
        if (state.brightness != 0) m = then(m, brightnessMatrix(state.brightness))
        if (state.contrast != 0) m = then(m, contrastForValue(state.contrast))
        if (state.saturation != 0) m = then(m, saturationForValue(state.saturation))
        if (state.hue != 0) m = then(m, hueMatrix(state.hue.toFloat()))
        return m
    }

    /** Applies [m] to one RGB value (0..255), clamped. Used by tests and previews. */
    fun apply(m: FloatArray, r: Int, g: Int, b: Int): IntArray {
        fun ch(row: Int): Int {
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return intArrayOf(ch(0), ch(1), ch(2))
    }
}
