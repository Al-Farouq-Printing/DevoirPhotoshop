package com.devoir.photoshop.engine.text

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.devoir.photoshop.engine.layers.FontCatalog
import com.devoir.photoshop.engine.layers.TextAlignment
import com.devoir.photoshop.engine.layers.TextSpec
import kotlin.math.ceil
import kotlin.math.max

/**
 * Text layout for text layers. The same function sizes the layer and draws it, so the layer's
 * bounds always match what is rendered. Layouts are cached by spec (small LRU), so redraws,
 * thumbnails and measuring never rebuild them. Main-thread only.
 */
object TextEngine {
    class Result(val layout: StaticLayout, val width: Float, val height: Float)

    private const val MAX_CACHE = 96
    private const val UNBOUNDED_WIDTH = 16_384

    private var assets: AssetManager? = null
    private val typefaces = HashMap<String, Typeface>()
    private val cache = object : LinkedHashMap<TextSpec, Result>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextSpec, Result>?) = size > MAX_CACHE
    }

    /** Enables bundled fonts (FontOption.assetPath). Safe to skip: those fonts fall back to the default. */
    fun init(assetManager: AssetManager) { assets = assetManager }

    fun typeface(fontId: String, bold: Boolean, italic: Boolean): Typeface =
        typefaces.getOrPut("$fontId|$bold|$italic") {
            val option = FontCatalog.byId(fontId)
            val am = assets
            val base = try {
                when {
                    option.assetPath != null && am != null -> Typeface.createFromAsset(am, option.assetPath)
                    option.family != null -> Typeface.create(option.family, Typeface.NORMAL)
                    else -> Typeface.DEFAULT
                }
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
            val style = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style == Typeface.NORMAL) base else Typeface.create(base, style)
        }

    fun layoutFor(spec: TextSpec): Result = cache.getOrPut(spec) { compute(spec) }

    private fun compute(spec: TextSpec): Result {
        val s = spec.sanitized()
        val text: CharSequence = s.text.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = s.fontSize
            color = s.color
            typeface = typeface(s.fontId, s.bold, s.italic)
            letterSpacing = s.letterSpacing
        }
        val boxWidth = s.boxWidth
        val width = if (boxWidth != null) {
            ceil(boxWidth).toInt().coerceAtLeast(1)
        } else {
            // Auto-size: the widest line decides the width, so alignment still works for multi-line text.
            val probe = build(text, paint, UNBOUNDED_WIDTH, s)
            var widest = 0f
            for (i in 0 until probe.lineCount) widest = max(widest, probe.getLineWidth(i))
            ceil(widest).toInt().coerceAtLeast(1)
        }
        val layout = build(text, paint, width, s)
        return Result(layout, width.toFloat(), layout.height.toFloat().coerceAtLeast(1f))
    }

    private fun build(text: CharSequence, paint: TextPaint, width: Int, s: TextSpec): StaticLayout {
        // Left/Right are visual: for right-to-left scripts (e.g. Arabic) "start" is the right edge.
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
        val alignment = when (s.alignment) {
            TextAlignment.Left -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            TextAlignment.Right -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            TextAlignment.Center -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.Justify -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, s.lineSpacing)
            .setIncludePad(true)
        if (s.alignment == TextAlignment.Justify) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }
}
