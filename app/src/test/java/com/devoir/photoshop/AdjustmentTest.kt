package com.devoir.photoshop

import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.adjust.AdjustmentMatrix
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.adjust.FilterPreset
import com.devoir.photoshop.engine.adjust.PixelOps
import com.devoir.photoshop.engine.adjust.TonalParams
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests of the colour maths and the pixel passes (no Android classes involved). */
class AdjustmentTest {
    private fun px(s: AdjustmentState, r: Int, g: Int, b: Int) = AdjustmentMatrix.apply(AdjustmentMatrix.build(s), r, g, b)
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---- state ----
    @Test fun defaultsAreNeutral() {
        val d = AdjustmentState()
        assertTrue(d.isDefault); assertTrue(d.isColorIdentity); assertTrue(d.tonal().isIdentity)
        assertTrue(AdjustmentMatrix.isIdentity(AdjustmentMatrix.build(d)))
        assertEquals(FilterPreset.Original, d.filter)
    }
    @Test fun sanitisedRanges() {
        val s = AdjustmentState(brightness = 500, hue = -999, sharpness = -5, temperature = 101).sanitized()
        assertEquals(100, s.brightness); assertEquals(-180, s.hue); assertEquals(0, s.sharpness); assertEquals(100, s.temperature)
    }
    @Test fun resetsAreIndependent() {
        val s = AdjustmentState(filter = FilterPreset.Vintage, brightness = 20, contrast = 10, sharpness = 30)
        assertEquals(FilterPreset.Vintage, s.withoutValues().filter); assertTrue(s.withoutValues().colorOnly().copy(filter = FilterPreset.Original).isDefault)
        assertEquals(FilterPreset.Original, s.withoutFilter().filter)
        assertEquals(20, s.withoutFilter().brightness); assertEquals(10, s.withoutFilter().contrast); assertEquals(30, s.withoutFilter().sharpness)
        assertTrue(AdjustmentState().isDefault)
    }
    @Test fun copiesAreIndependentValues() {
        val a = AdjustmentState(brightness = 30)
        val b = a.copy(brightness = -20)
        assertEquals(30, a.brightness); assertEquals(-20, b.brightness) // "duplicate then edit" cannot touch the original
    }
    @Test fun tonalFieldsDoNotChangeColourKey() {
        val a = AdjustmentState(brightness = 5, sharpness = 40, highlights = 20)
        assertEquals(a.colorOnly(), a.copy(sharpness = 0, shadows = 50).colorOnly())
        assertEquals(TonalParams(20, 0, 40), a.tonal())
    }

    // ---- colour maths ----
    @Test fun brightness() {
        assertEquals(202, px(AdjustmentState(brightness = 100), 100, 100, 100)[0])
        assertTrue(px(AdjustmentState(brightness = -100), 100, 100, 100)[0] < 100)
        assertArrayEquals(intArrayOf(100, 120, 140), px(AdjustmentState(), 100, 120, 140))
    }
    @Test fun contrastPivotsAroundMidGrey() {
        val s = AdjustmentState(contrast = 50)
        assertEquals(128.0, px(s, 128, 128, 128)[0].toDouble(), 1.0)
        assertTrue(px(s, 200, 200, 200)[0] > 200); assertTrue(px(s, 60, 60, 60)[0] < 60)
        val low = AdjustmentState(contrast = -50)
        assertTrue(px(low, 200, 200, 200)[0] < 200); assertTrue(px(low, 60, 60, 60)[0] > 60)
    }
    @Test fun saturation() {
        val grey = px(AdjustmentState(saturation = -100), 255, 0, 0)
        assertEquals(grey[0], grey[1]); assertEquals(grey[1], grey[2]); assertEquals(76.0, grey[0].toDouble(), 1.0)
        // +50 spreads the channels but keeps the luma (brightness is not changed)
        val out = px(AdjustmentState(saturation = 50), 200, 100, 50)
        assertTrue(out[0] - out[2] > 200 - 50)
        fun luma(c: IntArray) = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
        assertEquals(luma(intArrayOf(200, 100, 50)), luma(out), 2.0)
    }
    @Test fun hue() {
        assertArrayEquals(intArrayOf(200, 100, 50), px(AdjustmentState(hue = 0), 200, 100, 50))
        val cyan = px(AdjustmentState(hue = 180), 255, 0, 0)
        assertTrue(cyan[0] < 20 && cyan[1] > 90 && cyan[2] > 90)
        assertEquals(px(AdjustmentState(hue = 360.coerceAtMost(180)), 255, 0, 0)[0], cyan[0]) // range is clamped
    }
    @Test fun exposureIsAGain() {
        assertEquals(120, px(AdjustmentState(exposure = 50), 60, 60, 60)[0])
        assertEquals(30, px(AdjustmentState(exposure = -50), 60, 60, 60)[0])
    }
    @Test fun temperatureAndTint() {
        val warm = px(AdjustmentState(temperature = 100), 100, 100, 100)
        assertTrue(warm[0] > 100 && warm[2] < 100 && warm[1] == 100)
        val cool = px(AdjustmentState(temperature = -100), 100, 100, 100)
        assertTrue(cool[0] < 100 && cool[2] > 100)
        val magenta = px(AdjustmentState(tint = 100), 100, 100, 100)
        assertTrue(magenta[1] < 100 && magenta[0] > 100)
        val green = px(AdjustmentState(tint = -100), 100, 100, 100)
        assertTrue(green[1] > 100)
    }

    // ---- filters ----
    @Test fun everyFilterHasARealEffectAndIsDistinct() {
        val ms = FilterPreset.entries.map { AdjustmentMatrix.filterMatrix(it) }
        assertTrue(AdjustmentMatrix.isIdentity(ms[0]))
        for (i in 1 until ms.size) assertFalse(FilterPreset.entries[i].name, AdjustmentMatrix.isIdentity(ms[i]))
        for (i in ms.indices) for (j in i + 1 until ms.size) assertFalse("${FilterPreset.entries[i]} vs ${FilterPreset.entries[j]}", ms[i].contentEquals(ms[j]))
    }
    @Test fun filterBehaviour() {
        fun f(p: FilterPreset, r: Int, g: Int, b: Int) = px(AdjustmentState(filter = p), r, g, b)
        assertArrayEquals(intArrayOf(245, 235, 225), f(FilterPreset.Invert, 10, 20, 30))
        val gray = f(FilterPreset.Grayscale, 200, 100, 50); assertTrue(gray[0] == gray[1] && gray[1] == gray[2])
        val bw = f(FilterPreset.BlackWhite, 200, 100, 50); assertTrue(bw[0] == bw[1] && bw[1] == bw[2])
        val sepia = f(FilterPreset.Sepia, 128, 128, 128); assertTrue(sepia[0] > sepia[1] && sepia[1] > sepia[2])
        val hc = f(FilterPreset.HighContrast, 60, 60, 60); assertTrue(hc[0] < 60); assertTrue(f(FilterPreset.HighContrast, 200, 200, 200)[0] > 200)
        val cool = f(FilterPreset.Cool, 128, 128, 128); assertTrue(cool[2] > cool[0])
        val warm = f(FilterPreset.Warm, 128, 128, 128); assertTrue(warm[0] > warm[2])
        assertTrue(f(FilterPreset.Vintage, 0, 0, 0)[0] > 0) // faded blacks
    }
    @Test fun alphaRowIsAlwaysIdentity() {
        val full = AdjustmentState(FilterPreset.Vintage, 20, 30, -40, 10, 90, 15, -10, 30, -30, 50)
        for (m in FilterPreset.entries.map { AdjustmentMatrix.filterMatrix(it) } + listOf(AdjustmentMatrix.build(full))) {
            assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 1e-5f)
        }
    }

    // ---- order is fixed and documented ----
    @Test fun processingOrderIsDeterministic() {
        val s = AdjustmentState(brightness = 10, contrast = 20)
        assertArrayEquals(AdjustmentMatrix.build(s), AdjustmentMatrix.build(s), 0f)
        // brightness first, then contrast: 1.3 * (100 + 10.2) - 38.25 = 105; the reverse order would give ~102
        val out = px(s, 100, 100, 100)[0]
        assertTrue("got $out", out in 104..106)
    }
    @Test fun filterRunsBeforeAdjustments() {
        val warm = px(AdjustmentState(filter = FilterPreset.Warm), 100, 100, 100)
        val both = px(AdjustmentState(filter = FilterPreset.Warm, brightness = 10), 100, 100, 100)
        for (c in 0..2) assertEquals(warm[c] + 10.2, both[c].toDouble(), 1.0)
    }

    // ---- pixel stage ----
    @Test fun tonalCurveTargetsTheRightRange() {
        val shadows = PixelOps.tonalLut(0, 100)
        assertTrue(shadows[0] > 80); assertEquals(0, shadows[255]); assertTrue(shadows[30] > shadows[220] + 50)
        val highs = PixelOps.tonalLut(-100, 0)
        assertTrue(highs[255] < -80); assertEquals(0, highs[0]); assertTrue(-highs[220] > -highs[30] + 50)
        assertTrue(PixelOps.tonalLut(0, 0).all { it == 0 })
    }
    @Test fun tonalKeepsAlphaAndTransparentPixels() {
        val lut = PixelOps.tonalLut(0, 100)
        val buf = intArrayOf(argb(255, 50, 50, 50), argb(128, 50, 50, 50), 0x00123456)
        PixelOps.applyTonal(buf, 3, lut)
        assertTrue((buf[0] shr 16 and 255) > 50); assertEquals(255, buf[0] ushr 24)
        assertEquals(128, buf[1] ushr 24); assertEquals(0x00123456, buf[2])
    }
    @Test fun sharpenLeavesFlatAreasAlone() {
        val src = IntArray(8 * 3) { argb(255, 128, 128, 128) }
        val dst = IntArray(8)
        PixelOps.sharpenRows(src, 8, 3, 1, 1, 2f, dst)
        assertTrue(dst.all { it == argb(255, 128, 128, 128) })
    }
    @Test fun sharpenIncreasesEdgeContrast() {
        val src = IntArray(8 * 3) { i -> if (i % 8 < 4) argb(255, 50, 50, 50) else argb(255, 200, 200, 200) }
        val dst = IntArray(8)
        PixelOps.sharpenRows(src, 8, 3, 1, 1, 1f, dst)
        assertTrue((dst[3] and 255) < 50); assertTrue((dst[4] and 255) > 200)
        assertEquals(50, dst[0] and 255); assertEquals(200, dst[7] and 255) // away from the edge: unchanged
    }
    @Test fun sharpenNeverUsesTransparentNeighboursOrChangesAlpha() {
        val src = IntArray(9) { 0 }
        src[4] = argb(200, 255, 0, 0)
        val dst = IntArray(9)
        PixelOps.sharpenRows(src, 3, 3, 0, 3, 2f, dst)
        assertEquals(argb(200, 255, 0, 0), dst[4]) // no dark halo from the transparent black around it
        for (i in listOf(0, 1, 2, 3, 5, 6, 7, 8)) assertEquals(0, dst[i] ushr 24)
    }
    @Test fun sharpAmountRange() {
        assertEquals(0f, PixelOps.sharpAmount(0), 0f); assertEquals(2f, PixelOps.sharpAmount(100), 0f); assertEquals(2f, PixelOps.sharpAmount(500), 0f)
    }

    // ---- layer compatibility ----
    @Test fun adjustmentsAreRefusedOnNonImageLayers() {
        var d = CanvasDocument(ProjectConfig(100, 100))
        d = LayerManager.addPaintLayer(d, "p", "Paint").document
        assertNotNull(LayerManager.setAdjustments(d, "p", AdjustmentState(brightness = 10)).error)
        assertNotNull(LayerManager.setAdjustments(d, "missing", AdjustmentState()).error)
        assertNotNull(LayerManager.setAdjustments(d, "background", AdjustmentState()).error)
    }
}
