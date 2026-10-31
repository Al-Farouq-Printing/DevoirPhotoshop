package com.devoir.photoshop.core.project

import com.devoir.photoshop.model.BackgroundType
import com.devoir.photoshop.model.ProjectConfig
import com.devoir.photoshop.model.SizeUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

sealed interface ConfigResult {
    data class Valid(val config: ProjectConfig) : ConfigResult
    data class Invalid(
        val widthError: String? = null,
        val heightError: String? = null,
        val dpiError: String? = null,
        val colorError: String? = null,
    ) : ConfigResult
}

/** Pure unit/DPI maths and validation. No Android dependencies, so it is unit-testable. */
object ProjectMath {
    const val DEFAULT_DPI = 72
    const val MIN_DPI = 1
    const val MAX_DPI = 2400
    const val MAX_SIDE_PX = 10_000
    const val MAX_TOTAL_PX = 50_000_000L
    private const val CM_PER_INCH = 2.54
    private const val MM_PER_INCH = 25.4

    fun toPixels(value: Double, unit: SizeUnit, dpi: Int): Int {
        val px = when (unit) {
            SizeUnit.Pixels -> value
            SizeUnit.Inches -> value * dpi
            SizeUnit.Centimeters -> value / CM_PER_INCH * dpi
            SizeUnit.Millimeters -> value / MM_PER_INCH * dpi
        }
        return px.roundToInt()
    }

    fun fromPixels(px: Int, unit: SizeUnit, dpi: Int): Double = when (unit) {
        SizeUnit.Pixels -> px.toDouble()
        SizeUnit.Inches -> px.toDouble() / dpi
        SizeUnit.Centimeters -> px.toDouble() / dpi * CM_PER_INCH
        SizeUnit.Millimeters -> px.toDouble() / dpi * MM_PER_INCH
    }

    /** Same project settings with a new pixel size; the entered-unit values follow the DPI. */
    fun withPixelSize(c: ProjectConfig, w: Int, h: Int) = c.copy(
        widthPx = w, heightPx = h,
        widthInUnit = if (c.unit == SizeUnit.Pixels) w.toDouble() else fromPixels(w, c.unit, c.dpi),
        heightInUnit = if (c.unit == SizeUnit.Pixels) h.toDouble() else fromPixels(h, c.unit, c.dpi),
    )

    fun parseNumber(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** "RRGGBB" or "#RRGGBB" -> opaque ARGB int, or null. */
    fun parseHexColor(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6) return null
        val v = hex.toLongOrNull(16) ?: return null
        return (0xFF000000L or v).toInt()
    }

    fun formatValue(value: Double, unit: SizeUnit): String =
        if (unit == SizeUnit.Pixels) value.roundToInt().toString()
        else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    fun buildConfig(
        widthText: String,
        heightText: String,
        unit: SizeUnit,
        dpiText: String,
        background: BackgroundType,
        colorHex: String,
    ): ConfigResult {
        val w = parseNumber(widthText)
        val h = parseNumber(heightText)
        val dpi = dpiText.trim().toIntOrNull()

        val dpiError = if (dpi == null || dpi !in MIN_DPI..MAX_DPI) "DPI must be $MIN_DPI–$MAX_DPI" else null
        val canConvert = dpiError == null || unit == SizeUnit.Pixels
        val effectiveDpi = if (dpi != null && dpiError == null) dpi else DEFAULT_DPI

        fun dimError(v: Double?): String? {
            if (v == null || v <= 0.0) return "Enter a number greater than 0"
            if (!canConvert) return null
            val px = toPixels(v, unit, effectiveDpi)
            return when {
                px < 1 -> "Too small (under 1 px)"
                px > MAX_SIDE_PX -> "Max $MAX_SIDE_PX px per side"
                else -> null
            }
        }

        val wErr = dimError(w)
        var hErr = dimError(h)
        var wPx = 0
        var hPx = 0
        if (wErr == null && hErr == null && canConvert && w != null && h != null) {
            wPx = toPixels(w, unit, effectiveDpi)
            hPx = toPixels(h, unit, effectiveDpi)
            if (wPx.toLong() * hPx > MAX_TOTAL_PX) hErr = "Canvas exceeds ${MAX_TOTAL_PX / 1_000_000} megapixels"
        }

        val color = parseHexColor(colorHex)
        val colorErr = if (background == BackgroundType.Custom && color == null) "Use 6 hex digits (RRGGBB)" else null

        if (wErr != null || hErr != null || dpiError != null || colorErr != null || w == null || h == null) {
            return ConfigResult.Invalid(wErr, hErr, dpiError, colorErr)
        }
        return ConfigResult.Valid(
            ProjectConfig(
                widthPx = wPx,
                heightPx = hPx,
                unit = unit,
                widthInUnit = if (unit == SizeUnit.Pixels) wPx.toDouble() else w,
                heightInUnit = if (unit == SizeUnit.Pixels) hPx.toDouble() else h,
                dpi = effectiveDpi,
                backgroundType = background,
                backgroundColor = color ?: 0xFFFFFFFF.toInt(),
            ),
        )
    }

    /** Canvas config matching an image's size, shrunk proportionally if it exceeds the limits. */
    fun configFromImageSize(width: Int, height: Int): ProjectConfig {
        val side = min(1.0, MAX_SIDE_PX.toDouble() / max(width, height))
        val total = min(1.0, sqrt(MAX_TOTAL_PX.toDouble() / (width.toDouble() * height)))
        val f = min(side, total)
        return ProjectConfig(
            widthPx = max(1, (width * f).toInt()),
            heightPx = max(1, (height * f).toInt()),
            backgroundType = BackgroundType.Transparent,
        )
    }
}
