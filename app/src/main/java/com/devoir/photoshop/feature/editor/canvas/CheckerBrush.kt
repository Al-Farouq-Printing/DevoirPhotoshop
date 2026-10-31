package com.devoir.photoshop.feature.editor.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Tiled transparency checkerboard: one small bitmap, one draw call, cheap at any zoom. */
@Composable
fun rememberCheckerBrush(cell: Dp = 10.dp): Brush {
    val density = LocalDensity.current
    return remember(density.density, cell) {
        val c = with(density) { cell.toPx() }.roundToInt().coerceAtLeast(2)
        val size = c * 2
        val bmp = ImageBitmap(size, size)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.color = Color(0xFFFFFFFF)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = Color(0xFFCCCCCC)
        canvas.drawRect(0f, 0f, c.toFloat(), c.toFloat(), paint)
        canvas.drawRect(c.toFloat(), c.toFloat(), size.toFloat(), size.toFloat(), paint)
        ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
    }
}
