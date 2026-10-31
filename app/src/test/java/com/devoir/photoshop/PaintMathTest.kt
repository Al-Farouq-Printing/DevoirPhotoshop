package com.devoir.photoshop

import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.paint.BrushSettings
import com.devoir.photoshop.engine.paint.LayerGeometry
import com.devoir.photoshop.engine.paint.PaintSurfaceFactory
import com.devoir.photoshop.engine.paint.StrokeSampler
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PaintMathTest {
    private fun layer(w: Float, h: Float, t: LayerTransform) =
        Layer("l", "L", LayerType.Paint, LayerContent.Paint(), w, h, t)

    @Test fun identityMapping() {
        val l = layer(1000f, 500f, LayerTransform(500f, 250f))
        val p = LayerGeometry.docToLocal(l, 123f, 45f)
        assertEquals(123f, p.x, 0.001f); assertEquals(45f, p.y, 0.001f)
    }
    @Test fun movedAndScaledMapping() {
        val l = layer(100f, 100f, LayerTransform(200f, 200f, scaleX = 2f, scaleY = 2f))
        // layer centre maps to local centre; 50 doc px right of centre = 25 local px
        val c = LayerGeometry.docToLocal(l, 200f, 200f); assertEquals(50f, c.x, 0.001f); assertEquals(50f, c.y, 0.001f)
        val r = LayerGeometry.docToLocal(l, 250f, 200f); assertEquals(75f, r.x, 0.001f)
    }
    @Test fun rotatedMapping() {
        val l = layer(100f, 100f, LayerTransform(50f, 50f, rotation = 90f))
        val p = LayerGeometry.docToLocal(l, 60f, 50f) // renderer: local (50,40) -> doc (60,50)
        assertEquals(50f, p.x, 0.01f); assertEquals(40f, p.y, 0.01f)
    }

    @Test fun samplerHasNoGapsAndReachesEnd() {
        val pts = ArrayList<Pair<Float, Float>>()
        val s = StrokeSampler(10f) { x, y -> pts.add(x to y) }
        s.begin(0f, 0f); s.add(100f, 0f); s.end()
        assertEquals(0f, pts.first().first, 0.001f)
        assertTrue(pts.last().first in 90f..100.01f)
        for (i in 1 until pts.size) assertEquals(10f, hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second), 0.05f)
    }
    @Test fun tapMakesOneDab() {
        var n = 0
        val s = StrokeSampler(5f) { _, _ -> n++ }
        s.begin(3f, 3f); s.end()
        assertEquals(1, n)
    }
    @Test fun fastCornerStaysContinuous() {
        val pts = ArrayList<Pair<Float, Float>>()
        val s = StrokeSampler(4f) { x, y -> pts.add(x to y) }
        s.begin(0f, 0f); s.add(200f, 0f); s.add(200f, 200f); s.end()
        for (i in 1 until pts.size) assertTrue(hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second) < 4.1f)
    }

    @Test fun surfaceSizing() {
        assertEquals(1080, PaintSurfaceFactory.planSize(1080, 1920, 1_000_000_000L)!!.width)
        val big = PaintSurfaceFactory.planSize(10000, 5000, 1_000_000_000L)!!
        assertTrue(big.width.toLong() * big.height <= PaintSurfaceFactory.MAX_PAINT_PIXELS)
        assertEquals(2.0, big.width.toDouble() / big.height, 0.01)
        assertNull(PaintSurfaceFactory.planSize(1080, 1920, 100_000L))
        val tight = PaintSurfaceFactory.planSize(1080, 1920, 8_000_000L)!!
        assertTrue(tight.width.toLong() * tight.height <= 1_000_000L)
    }

    @Test fun brushDefaultsAndSizeMapping() {
        val d = BrushSettings()
        assertEquals(20f, d.size, 0f); assertEquals(1f, d.opacity, 0f); assertEquals(1f, d.hardness, 0f); assertEquals(0xFF000000.toInt(), d.color)
        assertEquals(BrushSettings.MIN_SIZE, BrushSettings.sliderToSize(0f), 0.001f)
        assertEquals(BrushSettings.MAX_SIZE, BrushSettings.sliderToSize(1f), 0.01f)
        assertTrue(BrushSettings.MAX_SIZE >= 1000f)
        assertEquals(37f, BrushSettings.sliderToSize(BrushSettings.sizeToSlider(37f)), 0.01f)
        val s = BrushSettings(size = 99999f, opacity = 3f, hardness = -1f, color = 0x00112233).sanitized()
        assertEquals(BrushSettings.MAX_SIZE, s.size, 0f); assertEquals(1f, s.opacity, 0f); assertEquals(0f, s.hardness, 0f)
        assertEquals(0xFF112233.toInt(), s.color)
    }

    @Test fun paintLayerInsertedAboveSelectionOrBackground() {
        var d = CanvasDocument(ProjectConfig(100, 100))
        d = LayerManager.addPaintLayer(d, "a", "A").document
        d = LayerManager.addPaintLayer(d, "b", "B").document
        val above = LayerManager.addPaintLayerAbove(d, "n", "N", "a").document
        assertEquals(listOf("a", "n", "b"), above.layers.map { it.id }); assertEquals("n", above.selectedLayerId)
        val fromBackground = LayerManager.addPaintLayerAbove(d, "m", "M", "background").document
        assertEquals(listOf("m", "a", "b"), fromBackground.layers.map { it.id })
    }
    @Test fun revisionBumpsOnlyPaintLayers() {
        var d = CanvasDocument(ProjectConfig(100, 100))
        d = LayerManager.addPaintLayer(d, "a", "A").document
        val bumped = LayerManager.bumpRevision(d, "a").document
        assertEquals(1, bumped.layer("a")!!.content.revision)
        assertSame(d, LayerManager.bumpRevision(d, "missing").document)
    }
}
