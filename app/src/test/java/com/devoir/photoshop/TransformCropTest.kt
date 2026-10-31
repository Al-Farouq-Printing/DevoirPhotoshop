package com.devoir.photoshop

import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.crop.CropAspectOption
import com.devoir.photoshop.engine.crop.CropHandle
import com.devoir.photoshop.engine.crop.CropMath
import com.devoir.photoshop.engine.crop.CropRect
import com.devoir.photoshop.engine.layers.Anchor
import com.devoir.photoshop.engine.layers.DocumentOps
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.paint.LayerGeometry
import com.devoir.photoshop.engine.paint.Pt
import com.devoir.photoshop.engine.transform.TransformGeometry
import com.devoir.photoshop.engine.transform.TransformHandle
import com.devoir.photoshop.engine.transform.TransformMath
import com.devoir.photoshop.engine.transform.TransformSession
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests (no Android classes are instantiated). */
class TransformCropTest {
    // A "Shape"-typed layer with paint content: behaves like a layer with pixels for document operations.
    private fun layer(id: String, t: LayerTransform, w: Float = 100f, h: Float = 100f, type: LayerType = LayerType.Shape) =
        Layer(id, id, type, LayerContent.Paint(), w, h, t)
    private fun docWith(vararg l: Layer, w: Int = 1000, h: Int = 500) =
        CanvasDocument(ProjectConfig(w, h), layers = l.toList(), selectedLayerId = l.last().id)

    // ---- transform maths ----
    @Test fun angleNormalisationAndSnap() {
        assertEquals(270f, TransformMath.normalizeDegrees(-90f), 0.001f)
        assertEquals(0f, TransformMath.normalizeDegrees(720f), 0.001f)
        assertEquals(90f, TransformMath.snapAngle(91.5f), 0.001f)
        assertEquals(60f, TransformMath.snapAngle(60f), 0.001f)
    }
    @Test fun moveFollowsFingerExactly() {
        val s = TransformSession(LayerTransform(100f, 100f), 100f, 100f, TransformHandle.Move, Pt(90f, 90f), true)
        val t = s.update(Pt(140f, 70f))
        assertEquals(150f, t.x, 0.001f); assertEquals(80f, t.y, 0.001f)
    }
    @Test fun rotateByHandle() {
        val s = TransformSession(LayerTransform(100f, 100f), 100f, 100f, TransformHandle.Rotate, Pt(200f, 100f), true)
        assertEquals(90f, s.update(Pt(100f, 200f)).rotation, 0.01f)
        assertEquals(270f, s.update(Pt(100f, 0f)).rotation, 0.01f)
    }
    @Test fun cornerScaleKeepsOppositeCornerFixed() {
        val s = TransformSession(LayerTransform(100f, 100f), 100f, 100f, TransformHandle.BottomRight, Pt(150f, 150f), false)
        val t = s.update(Pt(250f, 150f)) // drag right by 100 only
        assertEquals(2f, t.scaleX, 0.001f); assertEquals(1f, t.scaleY, 0.001f)
        assertEquals(150f, t.x, 0.001f); assertEquals(100f, t.y, 0.001f) // top-left stays at (50,50)
    }
    @Test fun lockedRatioScalesUniformly() {
        val s = TransformSession(LayerTransform(100f, 100f), 100f, 100f, TransformHandle.BottomRight, Pt(150f, 150f), true)
        val t = s.update(Pt(250f, 250f))
        assertEquals(t.scaleX, t.scaleY, 0.0001f); assertEquals(2f, t.scaleX, 0.001f)
    }
    @Test fun scaleNeverFlipsOrCollapses() {
        val s = TransformSession(LayerTransform(100f, 100f), 100f, 100f, TransformHandle.BottomRight, Pt(150f, 150f), false)
        val t = s.update(Pt(-500f, -500f)) // dragged far past the anchor
        assertEquals(TransformMath.MIN_SCALE, t.scaleX, 0.0001f); assertEquals(TransformMath.MIN_SCALE, t.scaleY, 0.0001f)
    }
    @Test fun rotatedScaleKeepsAnchor() {
        val start = LayerTransform(200f, 200f, rotation = 90f)
        val l = layer("a", start)
        val before = TransformGeometry.corners(l)[0] // top-left = anchor for a bottom-right drag
        val handle = TransformGeometry.corners(l)[2]
        val grown = TransformSession(start, 100f, 100f, TransformHandle.BottomRight, handle, false)
            .update(Pt(handle.x + 40f, handle.y + 40f))
        val after = TransformGeometry.corners(l.copy(transform = grown))[0]
        assertEquals(before.x, after.x, 0.01f); assertEquals(before.y, after.y, 0.01f)
    }
    @Test fun hitTesting() {
        val l = layer("a", LayerTransform(200f, 200f))
        assertEquals(TransformHandle.BottomRight, TransformGeometry.hitTest(l, Pt(251f, 249f), 10f, 40f))
        assertEquals(TransformHandle.Move, TransformGeometry.hitTest(l, Pt(200f, 200f), 10f, 40f))
        assertEquals(TransformHandle.Rotate, TransformGeometry.hitTest(l, Pt(200f, 110f), 10f, 40f)) // top edge y=150, handle 40 above it
        assertNull(TransformGeometry.hitTest(l, Pt(500f, 500f), 10f, 40f))
    }

    // ---- flips, reset, lock ----
    @Test fun flipMirrorsAngleAndTogglesTwice() {
        val d = docWith(layer("a", LayerTransform(500f, 250f, rotation = 30f)))
        val once = LayerManager.flip(d, "a", true).document.layer("a")!!.transform
        assertTrue(once.flipH); assertEquals(330f, once.rotation, 0.001f)
        val twice = LayerManager.flip(LayerManager.flip(d, "a", true).document, "a", true).document.layer("a")!!.transform
        assertFalse(twice.flipH); assertEquals(30f, twice.rotation, 0.001f)
        assertTrue(LayerManager.flip(d, "a", false).document.layer("a")!!.transform.flipV)
    }
    @Test fun flippedLayerMapsPointsConsistently() {
        val l = layer("a", LayerTransform(100f, 100f, flipH = true))
        val p = LayerGeometry.docToLocal(l, 130f, 100f) // 30 right of centre, mirrored -> 30 left of local centre
        assertEquals(20f, p.x, 0.001f); assertEquals(50f, p.y, 0.001f)
    }
    @Test fun resetRestoresDefault() {
        val d = docWith(layer("a", LayerTransform(500f, 250f)))
        val moved = LayerManager.setTransform(d, "a", LayerTransform(10f, 10f, 2f, 3f, 45f, true, true)).document
        val reset = LayerManager.resetTransform(moved, "a").document.layer("a")!!.transform
        assertEquals(LayerTransform(500f, 250f), reset)
    }
    @Test fun lockedLayerRefusesEveryTransform() {
        val d = LayerManager.setLocked(docWith(layer("a", LayerTransform(500f, 250f))), "a", true).document
        assertNotNull(LayerManager.flip(d, "a", true).error)
        assertNotNull(LayerManager.resetTransform(d, "a").error)
        assertNotNull(LayerManager.setTransform(d, "a", LayerTransform(1f, 1f)).error)
        assertNotNull(LayerManager.translate(d, "a", 1f, 1f).error)
    }
    @Test fun invalidValuesCannotReachTheRenderer() {
        val d = docWith(layer("a", LayerTransform(500f, 250f)))
        val t = LayerManager.setTransform(d, "a", LayerTransform(Float.NaN, 5f, Float.NaN, -3f, Float.POSITIVE_INFINITY)).document.layer("a")!!.transform
        assertTrue(t.x.isFinite() && t.scaleX.isFinite() && t.rotation.isFinite())
        assertTrue(t.scaleY >= TransformMath.MIN_SCALE)
    }
    @Test fun transformingOneLayerLeavesOthersAlone() {
        val a = layer("a", LayerTransform(100f, 100f)); val b = layer("b", LayerTransform(300f, 300f))
        val d = LayerManager.setTransform(docWith(a, b), "a", LayerTransform(50f, 50f, 2f, 2f, 10f)).document
        assertEquals(b.transform, d.layer("b")!!.transform)
    }

    // ---- crop maths ----
    @Test fun cropStaysInsideDocument() {
        val r = CropMath.resize(CropRect(100f, 100f, 300f, 300f), CropHandle.BottomRight, 5000f, 5000f, 1000, 500, null)
        assertEquals(1000f, r.right, 0.001f); assertEquals(500f, r.bottom, 0.001f)
        val m = CropMath.move(CropRect(0f, 0f, 200f, 100f), -50f, 900f, 1000, 500)
        assertEquals(0f, m.left, 0.001f); assertEquals(400f, m.top, 0.001f)
    }
    @Test fun freeCropHasMinimumSize() {
        val r = CropMath.resize(CropRect(100f, 100f, 300f, 300f), CropHandle.Right, -5000f, 0f, 1000, 500, null)
        assertTrue(r.width >= CropMath.MIN_SIZE - 0.001f)
    }
    @Test fun aspectRatiosHold() {
        for (opt in listOf(CropAspectOption.Square, CropAspectOption.R4x3, CropAspectOption.R16x9, CropAspectOption.R9x16, CropAspectOption.Original)) {
            val ratio = CropMath.ratioOf(opt, 1000, 500, 1f)!!
            val fit = CropMath.fitAspect(CropMath.full(1000, 500), ratio, 1000, 500)
            assertEquals(ratio, fit.width / fit.height, 0.001f)
            assertTrue(fit.left >= 0f && fit.top >= 0f && fit.right <= 1000.01f && fit.bottom <= 500.01f)
            for (h in CropHandle.entries) {
                val r = CropMath.resize(fit, h, 77f, -33f, 1000, 500, ratio)
                if (h != CropHandle.Move) assertEquals("$opt $h", ratio, r.width / r.height, 0.01f)
                assertTrue("$opt $h inside", r.left >= -0.01f && r.top >= -0.01f && r.right <= 1000.01f && r.bottom <= 500.01f)
            }
        }
        assertNull(CropMath.ratioOf(CropAspectOption.Free, 10, 10, 1f))
    }

    // ---- document operations ----
    @Test fun cropShiftsLayersAndKeepsTheirSize() {
        val d = docWith(layer("a", LayerTransform(600f, 300f, 2f, 2f)))
        val r = DocumentOps.crop(d, 200, 100, 800, 300)
        assertNull(r.error)
        assertEquals(800, r.document.width); assertEquals(300, r.document.height)
        val t = r.document.layer("a")!!.transform
        assertEquals(400f, t.x, 0.001f); assertEquals(200f, t.y, 0.001f); assertEquals(2f, t.scaleX, 0f)
        assertEquals(400f, r.document.layer("a")!!.defaultTransform.x, 0.001f) // reset target follows the crop
    }
    @Test fun cropOutsideCanvasIsRejected() {
        val d = docWith(layer("a", LayerTransform(100f, 100f)))
        assertNotNull(DocumentOps.crop(d, 900, 0, 500, 100).error)
        assertNotNull(DocumentOps.crop(d, -1, 0, 10, 10).error)
    }
    @Test fun canvasResizeAnchors() {
        val d = docWith(layer("a", LayerTransform(500f, 250f)))
        val cfg = ProjectMath.withPixelSize(d.config, 1200, 700)
        val center = DocumentOps.resizeCanvas(d, cfg, Anchor.Center).document.layer("a")!!.transform
        assertEquals(600f, center.x, 0.001f); assertEquals(350f, center.y, 0.001f)
        val tl = DocumentOps.resizeCanvas(d, cfg, Anchor.TopLeft).document.layer("a")!!.transform
        assertEquals(500f, tl.x, 0.001f); assertEquals(250f, tl.y, 0.001f)
        val br = DocumentOps.resizeCanvas(d, cfg, Anchor.BottomRight).document.layer("a")!!.transform
        assertEquals(700f, br.x, 0.001f); assertEquals(450f, br.y, 0.001f); assertEquals(1f, br.scaleX, 0f)
    }
    @Test fun canvasShrinkDoesNotScaleLayers() {
        val d = docWith(layer("a", LayerTransform(500f, 250f, 1.5f, 1.5f)))
        val r = DocumentOps.resizeCanvas(d, ProjectMath.withPixelSize(d.config, 640, 360), Anchor.Center).document
        assertEquals(1.5f, r.layer("a")!!.transform.scaleX, 0f)
    }
    @Test fun imageResizeScalesPlacementNotPixels() {
        val d = docWith(layer("a", LayerTransform(500f, 250f, 1f, 1f)))
        val r = DocumentOps.resizeImage(d, ProjectMath.withPixelSize(d.config, 2000, 1000)).document
        val t = r.layer("a")!!.transform
        assertEquals(1000f, t.x, 0.001f); assertEquals(500f, t.y, 0.001f); assertEquals(2f, t.scaleX, 0.001f)
        assertEquals(100f, r.layer("a")!!.width, 0f) // base size (and the bitmap) untouched
    }
    @Test fun imageResizeRespectsLimits() {
        val d = docWith(layer("a", LayerTransform(500f, 250f)))
        assertNotNull(DocumentOps.resizeImage(d, ProjectMath.withPixelSize(d.config, 20000, 10000)).error)
    }
    @Test fun emptyPaintLayersRefitTheNewCanvas() {
        val p = layer("p", LayerTransform(500f, 250f), 1000f, 500f, LayerType.Paint)
        val r = DocumentOps.crop(docWith(p), 0, 0, 400, 200).document.layer("p")!!
        assertEquals(400f, r.width, 0f); assertEquals(200f, r.transform.x, 0.001f); assertEquals(100f, r.transform.y, 0.001f)
    }
    @Test fun cropKeepsUnitValuesInSync() {
        val cfg = ProjectMath.withPixelSize(ProjectConfig(1000, 500, dpi = 100, unit = com.devoir.photoshop.model.SizeUnit.Inches), 400, 200)
        assertEquals(4.0, cfg.widthInUnit, 0.0001); assertEquals(2.0, cfg.heightInUnit, 0.0001)
    }
}
