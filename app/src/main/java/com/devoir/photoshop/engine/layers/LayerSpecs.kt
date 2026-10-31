package com.devoir.photoshop.engine.layers

import com.devoir.photoshop.engine.paint.Pt
import com.devoir.photoshop.engine.transform.TransformMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

// ----------------------------------------------------------------------------- fonts

/** [family] is an Android system family name; [assetPath] loads a bundled font from the APK assets. */
data class FontOption(val id: String, val label: String, val family: String?, val assetPath: String? = null)

/**
 * Local fonts only. To bundle a font: put the .ttf in app/src/main/assets/fonts/ and add
 * FontOption("myfont", "My Font", null, "fonts/MyFont.ttf") below. Nothing is ever downloaded.
 */
object FontCatalog {
    const val DEFAULT_ID = "default"
    val all: List<FontOption> = listOf(
        FontOption(DEFAULT_ID, "Default", null),
        FontOption("sans", "Sans-serif", "sans-serif"),
        FontOption("serif", "Serif", "serif"),
        FontOption("mono", "Monospace", "monospace"),
        FontOption("sans-medium", "Sans medium", "sans-serif-medium"),
        FontOption("sans-light", "Sans light", "sans-serif-light"),
        FontOption("sans-condensed", "Sans condensed", "sans-serif-condensed"),
        FontOption("cursive", "Cursive", "cursive"),
        FontOption("casual", "Casual", "casual"),
    )

    fun byId(id: String): FontOption = all.firstOrNull { it.id == id } ?: all.first()
}

// ----------------------------------------------------------------------------- text

enum class TextAlignment { Left, Center, Right, Justify }

data class TextSize(val width: Float, val height: Float)

/** Everything needed to draw a text layer. Text stays data (never a bitmap), so it stays editable. */
data class TextSpec(
    val text: String = "",
    val fontId: String = FontCatalog.DEFAULT_ID,
    /** Document pixels. */
    val fontSize: Float = 64f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** ARGB; the alpha channel is the text transparency. */
    val color: Int = 0xFF000000.toInt(),
    val alignment: TextAlignment = TextAlignment.Left,
    /** In em (fraction of font size). */
    val letterSpacing: Float = 0f,
    /** Line-height multiplier. */
    val lineSpacing: Float = 1f,
    /** null = auto-size to the text; otherwise text wraps inside this width. */
    val boxWidth: Float? = null,
) {
    fun sanitized() = copy(
        text = text.take(MAX_CHARS),
        fontId = FontCatalog.byId(fontId).id,
        fontSize = fontSize.takeIf { it.isFinite() }?.coerceIn(MIN_SIZE, MAX_SIZE) ?: 64f,
        letterSpacing = letterSpacing.takeIf { it.isFinite() }?.coerceIn(-0.1f, 1f) ?: 0f,
        lineSpacing = lineSpacing.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f) ?: 1f,
        boxWidth = boxWidth?.takeIf { it.isFinite() }?.coerceIn(20f, 20_000f),
    )

    companion object {
        const val MIN_SIZE = 8f
        const val MAX_SIZE = 500f
        const val MAX_CHARS = 10_000

        /** A readable default for the document: roughly a tenth of its short side. */
        fun defaultFor(minSide: Int) = TextSpec(fontSize = (minSide / 10f).coerceIn(16f, 200f))

        fun sizeToSlider(size: Float): Float = ln(size.coerceIn(MIN_SIZE, MAX_SIZE) / MIN_SIZE) / ln(MAX_SIZE / MIN_SIZE)
        fun sliderToSize(t: Float): Float = MIN_SIZE * (MAX_SIZE / MIN_SIZE).pow(t.coerceIn(0f, 1f))
    }
}

// ----------------------------------------------------------------------------- shapes

enum class ShapeType(val label: String) {
    Rectangle("Rectangle"), RoundedRectangle("Rounded rectangle"), Circle("Circle"), Ellipse("Ellipse"),
    Line("Line"), Polygon("Polygon"), Star("Star"),
}

/** Procedural shape: drawn from these numbers every frame; never rasterised. */
data class ShapeSpec(
    val type: ShapeType = ShapeType.Rectangle,
    /** ARGB; alpha = fill opacity. */
    val fillColor: Int = 0xFF3F7CF4.toInt(),
    /** ARGB; alpha = stroke opacity. For a Line this is the line colour. */
    val strokeColor: Int = 0xFF1B1B1B.toInt(),
    /** Document pixels, 0..100. 0 = no stroke (a Line is always at least 1). */
    val strokeWidth: Float = 0f,
    val cornerRadius: Float = 0f,
    val roundCaps: Boolean = true,
    /** Polygon sides / star points. */
    val sides: Int = 5,
) {
    fun sanitized() = copy(
        strokeWidth = (strokeWidth.takeIf { it.isFinite() } ?: 0f).coerceIn(if (type == ShapeType.Line) 1f else 0f, MAX_STROKE),
        cornerRadius = (cornerRadius.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 10_000f),
        sides = sides.coerceIn(3, 12),
    )

    /** A new spec of [newType] that keeps this spec's colours and sensible type defaults. */
    fun newOfType(newType: ShapeType): ShapeSpec = when (newType) {
        ShapeType.Line -> copy(type = newType, strokeWidth = max(strokeWidth, 8f), cornerRadius = 0f)
        ShapeType.RoundedRectangle -> copy(type = newType, cornerRadius = max(cornerRadius, 32f))
        ShapeType.Rectangle -> copy(type = newType, cornerRadius = 0f)
        else -> copy(type = newType, cornerRadius = 0f)
    }.sanitized()

    companion object { const val MAX_STROKE = 100f }
}

/** Where a shape sits: centre, size and rotation in document pixels / degrees. */
data class ShapeBounds(val cx: Float, val cy: Float, val width: Float, val height: Float, val rotation: Float)

object ShapeGeometry {
    const val MIN_SIDE = 1f

    /** A line's layer box is thin but never thinner than a finger-friendly 12 px (or its stroke). */
    fun lineBoxHeight(strokeWidth: Float) = max(12f, strokeWidth)

    /** Bounds for a shape dragged from [p0] to [p1]. A line is horizontal in its own frame, rotated to the drag. */
    fun fromDrag(type: ShapeType, p0: Pt, p1: Pt, strokeWidth: Float): ShapeBounds {
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        return when (type) {
            ShapeType.Line -> ShapeBounds(
                (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f, max(hypot(dx, dy), MIN_SIDE), lineBoxHeight(strokeWidth),
                TransformMath.normalizeDegrees(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()),
            )
            ShapeType.Circle -> {
                val side = max(abs(dx), abs(dy)).coerceAtLeast(MIN_SIDE)
                val sx = if (dx < 0f) -1f else 1f
                val sy = if (dy < 0f) -1f else 1f
                ShapeBounds(p0.x + sx * side / 2f, p0.y + sy * side / 2f, side, side, 0f)
            }
            else -> ShapeBounds(
                (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f, max(abs(dx), MIN_SIDE), max(abs(dy), MIN_SIDE), 0f,
            )
        }
    }

    /** What a plain tap creates. */
    fun defaultBounds(type: ShapeType, center: Pt, size: Float, strokeWidth: Float): ShapeBounds = when (type) {
        ShapeType.Line -> ShapeBounds(center.x, center.y, size, lineBoxHeight(strokeWidth), 0f)
        ShapeType.Ellipse -> ShapeBounds(center.x, center.y, size, size * 0.65f, 0f)
        else -> ShapeBounds(center.x, center.y, size, size, 0f)
    }
}
