package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.text.TextEngine
import com.devoir.photoshop.feature.editor.canvas.drawShape
import kotlin.math.min

/** Scaled-down vector preview of the real text layout. No bitmap is created. */
@Composable
fun TextThumb(spec: TextSpec, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = TextEngine.layoutFor(spec)
        val s = min(size.width / r.width, size.height / r.height)
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.translate((size.width - r.width * s) / 2f, (size.height - r.height * s) / 2f)
            n.scale(s, s)
            r.layout.draw(n)
            n.restore()
        }
    }
}

/** Scaled-down render of the actual shape (w x h = displayed size). */
@Composable
fun ShapeThumb(spec: ShapeSpec, w: Float, h: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val bw = w.coerceAtLeast(1f)
        val bh = h.coerceAtLeast(1f)
        val s = min(size.width / bw, size.height / bh)
        withTransform({
            translate((size.width - bw * s) / 2f, (size.height - bh * s) / 2f)
            scale(s, s, Offset.Zero)
        }) { drawShape(spec, bw, bh) }
    }
}
