package com.devoir.photoshop

import com.devoir.photoshop.core.project.ConfigResult
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.ImagePlacement
import com.devoir.photoshop.engine.image.ImageSampling
import com.devoir.photoshop.model.BackgroundType
import com.devoir.photoshop.model.CanvasOrientation
import com.devoir.photoshop.model.SizeUnit
import org.junit.Assert.*
import org.junit.Test

class ProjectMathTest {
    private fun build(w: String, h: String, u: SizeUnit, dpi: String = "72", bg: BackgroundType = BackgroundType.White, hex: String = "FF0000") =
        ProjectMath.buildConfig(w, h, u, dpi, bg, hex)

    @Test fun a4At72Dpi() {
        val c = (build("210", "297", SizeUnit.Millimeters) as ConfigResult.Valid).config
        assertEquals(595, c.widthPx); assertEquals(842, c.heightPx)
    }
    @Test fun a4At300Dpi() {
        val c = (build("210", "297", SizeUnit.Millimeters, "300") as ConfigResult.Valid).config
        assertEquals(2480, c.widthPx); assertEquals(3508, c.heightPx); assertEquals(300, c.dpi)
    }
    @Test fun inchesAndCm() {
        assertEquals(1200, ProjectMath.toPixels(4.0, SizeUnit.Inches, 300))
        assertEquals(1181, ProjectMath.toPixels(10.0, SizeUnit.Centimeters, 300))
    }
    @Test fun orientation() {
        assertEquals(CanvasOrientation.Portrait, (build("1080", "1920", SizeUnit.Pixels) as ConfigResult.Valid).config.orientation)
        assertEquals(CanvasOrientation.Landscape, (build("1920", "1080", SizeUnit.Pixels) as ConfigResult.Valid).config.orientation)
        assertEquals(CanvasOrientation.Square, (build("500", "500", SizeUnit.Pixels) as ConfigResult.Valid).config.orientation)
    }
    @Test fun rejectsBadInput() {
        assertTrue(build("0", "100", SizeUnit.Pixels) is ConfigResult.Invalid)
        assertTrue(build("abc", "100", SizeUnit.Pixels) is ConfigResult.Invalid)
        assertTrue(build("NaN", "100", SizeUnit.Pixels) is ConfigResult.Invalid)
        assertTrue(build("20000", "100", SizeUnit.Pixels) is ConfigResult.Invalid)
        assertTrue(build("9000", "9000", SizeUnit.Pixels) is ConfigResult.Invalid) // > 50 MP
        assertTrue(build("100", "100", SizeUnit.Pixels, "0") is ConfigResult.Invalid)
        assertTrue(build("100", "100", SizeUnit.Pixels, bg = BackgroundType.Custom, hex = "XYZ") is ConfigResult.Invalid)
    }
    @Test fun customColor() {
        val c = (build("100", "100", SizeUnit.Pixels, bg = BackgroundType.Custom, hex = "#336699") as ConfigResult.Valid).config
        assertEquals(0xFF336699.toInt(), c.backgroundColor)
    }
    @Test fun decimalComma() {
        val c = (build("2,5", "2,5", SizeUnit.Inches, "100") as ConfigResult.Valid).config
        assertEquals(250, c.widthPx)
    }
    @Test fun imageSizeConfigIsCapped() {
        val c = ProjectMath.configFromImageSize(20000, 10000)
        assertTrue(c.widthPx <= ProjectMath.MAX_SIDE_PX && c.heightPx <= ProjectMath.MAX_SIDE_PX)
        assertEquals(2.0, c.widthPx.toDouble() / c.heightPx, 0.01)
    }
    @Test fun sampling() {
        assertEquals(1, ImageSampling.sampleSize(4000, 3000, 4096, 16_777_216))
        assertEquals(2, ImageSampling.sampleSize(8000, 6000, 4096, 16_777_216))
        assertEquals(8, ImageSampling.sampleSize(30000, 20000, 4096, 16_777_216))
    }
    @Test fun placementKeepsAspectAndNeverUpscales() {
        val big = ImagePlacement.fit(1000f, 1000f, 4000f, 2000f)
        assertEquals(1000f, big.width, 0.01f); assertEquals(500f, big.height, 0.01f); assertEquals(250f, big.top, 0.01f)
        val small = ImagePlacement.fit(1000f, 1000f, 200f, 100f)
        assertEquals(200f, small.width, 0.01f); assertEquals(400f, small.left, 0.01f)
    }
}
