package com.devoir.photoshop

import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.DocumentOps
import com.devoir.photoshop.engine.layers.FontCatalog
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.engine.layers.LayerResult
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.LayerType
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.engine.layers.ShapeGeometry
import com.devoir.photoshop.engine.layers.ShapeSpec
import com.devoir.photoshop.engine.layers.ShapeType
import com.devoir.photoshop.engine.layers.TextAlignment
import com.devoir.photoshop.engine.layers.TextSize
import com.devoir.photoshop.engine.layers.TextSpec
import com.devoir.photoshop.engine.paint.Pt
import com.devoir.photoshop.engine.transform.TransformGeometry
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests: text sizes are passed in, so no Android text classes are needed. */
class TextShapeTest {
    private fun doc() = CanvasDocument(ProjectConfig(1000, 500))
    private fun ok(r: LayerResult): CanvasDocument { assertNull(r.error); return r.document }
    private fun withText(doc: CanvasDocument = doc(), id: String = "t", spec: TextSpec = TextSpec(text = "Hello")) =
        ok(LayerManager.addTextLayer(doc, id, spec, TextSize(200f, 50f), 100f, 50f))
    private fun spec(d: CanvasDocument, id: String) = (d.layer(id)!!.content as LayerContent.Text).spec
    private fun shape(d: CanvasDocument, id: String) = (d.layer(id)!!.content as LayerContent.Shape).spec

    // ---- specs ----
    @Test fun textSpecIsSanitised() {
        val s = TextSpec(text = "x", fontId = "nope", fontSize = 9999f, letterSpacing = 9f, lineSpacing = 0f, boxWidth = 1f).sanitized()
        assertEquals(TextSpec.MAX_SIZE, s.fontSize, 0f); assertEquals(FontCatalog.DEFAULT_ID, s.fontId)
        assertEquals(1f, s.letterSpacing, 0f); assertEquals(0.5f, s.lineSpacing, 0f); assertEquals(20f, s.boxWidth!!, 0f)
        assertEquals(64f, TextSpec(fontSize = Float.NaN).sanitized().fontSize, 0f)
    }
    @Test fun fontCatalogHasRequiredLocalFonts() {
        val labels = FontCatalog.all.map { it.label }
        assertTrue(labels.containsAll(listOf("Default", "Sans-serif", "Serif", "Monospace")))
        assertEquals(FontCatalog.DEFAULT_ID, FontCatalog.byId("missing").id)
        assertTrue(FontCatalog.all.all { it.assetPath == null }) // nothing is downloaded or bundled yet
    }
    @Test fun fontSizeSliderMapping() {
        assertEquals(8f, TextSpec.sliderToSize(0f), 0.01f); assertEquals(500f, TextSpec.sliderToSize(1f), 0.1f)
        assertEquals(120f, TextSpec.sliderToSize(TextSpec.sizeToSlider(120f)), 0.01f)
        assertEquals(108f, TextSpec.defaultFor(1080).fontSize, 0.001f)
    }
    @Test fun shapeSpecSanitisedAndTyped() {
        val s = ShapeSpec(type = ShapeType.Line, strokeWidth = 0f, sides = 99).sanitized()
        assertEquals(1f, s.strokeWidth, 0f); assertEquals(12, s.sides)
        assertEquals(100f, ShapeSpec(strokeWidth = 500f).sanitized().strokeWidth, 0f)
        assertTrue(ShapeSpec().newOfType(ShapeType.Line).strokeWidth >= 8f)
        assertTrue(ShapeSpec().newOfType(ShapeType.RoundedRectangle).cornerRadius >= 32f)
        assertEquals(0f, ShapeSpec(cornerRadius = 40f).newOfType(ShapeType.Rectangle).cornerRadius, 0f)
    }

    // ---- shape geometry ----
    @Test fun dragInAnyDirectionGivesPositiveBox() {
        val b = ShapeGeometry.fromDrag(ShapeType.Rectangle, Pt(300f, 200f), Pt(100f, 50f), 0f)
        assertEquals(200f, b.cx, 0.001f); assertEquals(125f, b.cy, 0.001f); assertEquals(200f, b.width, 0.001f); assertEquals(150f, b.height, 0.001f)
    }
    @Test fun circleIsAlwaysSquare() {
        val b = ShapeGeometry.fromDrag(ShapeType.Circle, Pt(100f, 100f), Pt(40f, 300f), 0f)
        assertEquals(b.width, b.height, 0f); assertEquals(200f, b.width, 0.001f)
        assertTrue(b.cx < 100f && b.cy > 100f)
        val e = ShapeGeometry.fromDrag(ShapeType.Ellipse, Pt(100f, 100f), Pt(300f, 160f), 0f)
        assertNotEquals(e.width, e.height)
    }
    @Test fun lineIsHorizontalInItsFrameAndRotatedToTheDrag() {
        val down = ShapeGeometry.fromDrag(ShapeType.Line, Pt(100f, 100f), Pt(100f, 300f), 6f)
        assertEquals(200f, down.width, 0.001f); assertEquals(90f, down.rotation, 0.01f); assertEquals(200f, down.cy, 0.001f)
        assertEquals(12f, down.height, 0f) // finger-friendly minimum thickness
        assertEquals(40f, ShapeGeometry.lineBoxHeight(40f), 0f)
        assertEquals(180f, ShapeGeometry.fromDrag(ShapeType.Line, Pt(100f, 100f), Pt(0f, 100f), 6f).rotation, 0.01f)
        assertTrue(ShapeGeometry.fromDrag(ShapeType.Line, Pt(5f, 5f), Pt(5f, 5f), 6f).width >= 1f) // a tap never gives a 0-size box
    }

    // ---- text layers ----
    @Test fun addTextCreatesAnIndependentSelectedLayer() {
        val d = withText()
        val l = d.layer("t")!!
        assertEquals(LayerType.Text, l.type); assertEquals("t", d.selectedLayerId)
        assertEquals(200f, l.transform.x, 0.001f); assertEquals(75f, l.transform.y, 0.001f)
        assertEquals("Hello", l.name); assertNull(l.content.resource) // no bitmap
        assertNotNull(LayerManager.addTextLayer(doc(), "x", TextSpec(text = "  "), TextSize(1f, 1f), 0f, 0f).error)
    }
    @Test fun multiLineNameUsesFirstLine() {
        assertEquals("Line 1", LayerManager.autoTextName("\n  Line 1\nLine 2"))
        assertEquals("Text", LayerManager.autoTextName("   "))
    }
    @Test fun editingTextKeepsIdAndTopLeft() {
        val d = withText()
        val e = ok(LayerManager.updateText(d, "t", TextSpec(text = "Hello World"), TextSize(300f, 80f)))
        assertEquals(1, e.layers.size); assertEquals("t", e.layers[0].id)
        assertEquals("Hello World", spec(e, "t").text); assertEquals("Hello World", e.layer("t")!!.name) // auto-name follows
        assertEquals(250f, e.layer("t")!!.transform.x, 0.001f); assertEquals(90f, e.layer("t")!!.transform.y, 0.001f)
        assertEquals(300f, e.layer("t")!!.width, 0f)
    }
    @Test fun editingKeepsTopLeftWhenRotated() {
        val d0 = withText()
        val d = ok(LayerManager.setTransform(d0, "t", d0.layer("t")!!.transform.copy(rotation = 90f)))
        val before = TransformGeometry.corners(d.layer("t")!!)[0]
        val e = ok(LayerManager.updateText(d, "t", TextSpec(text = "Hello World"), TextSize(300f, 80f)))
        val after = TransformGeometry.corners(e.layer("t")!!)[0]
        assertEquals(before.x, after.x, 0.01f); assertEquals(before.y, after.y, 0.01f)
    }
    @Test fun customNameSurvivesEditing() {
        val d = ok(LayerManager.rename(withText(), "t", "Title"))
        assertEquals("Title", ok(LayerManager.updateText(d, "t", TextSpec(text = "Other"), TextSize(100f, 50f))).layer("t")!!.name)
    }
    @Test fun textEditRulesAreEnforced() {
        val d = withText()
        assertNotNull(LayerManager.updateText(d, "t", TextSpec(text = ""), TextSize(1f, 1f)).error)
        val locked = ok(LayerManager.setLocked(d, "t", true))
        assertNotNull(LayerManager.updateText(locked, "t", TextSpec(text = "x"), TextSize(1f, 1f)).error)
        val shapeDoc = ok(LayerManager.addShapeLayer(doc(), "s", "S", ShapeSpec(), ShapeGeometry.defaultBounds(ShapeType.Rectangle, Pt(10f, 10f), 20f, 0f)))
        assertNotNull(LayerManager.updateText(shapeDoc, "s", TextSpec(text = "x"), TextSize(1f, 1f)).error)
    }
    @Test fun duplicatedTextIsIndependent() {
        val d = ok(LayerManager.duplicate(withText(spec = TextSpec(text = "Hi", bold = true, color = 0x80FF0000.toInt(), alignment = TextAlignment.Center)), "t", "t2"))
        assertEquals(listOf("t", "t2"), d.layers.map { it.id }) // copy sits above the original
        assertEquals(spec(d, "t"), spec(d, "t2")); assertEquals("Hi Copy", d.layer("t2")!!.name)
        val edited = ok(LayerManager.updateText(d, "t2", TextSpec(text = "Changed"), TextSize(50f, 50f)))
        assertEquals("Hi", spec(edited, "t").text); assertEquals("Changed", spec(edited, "t2").text)
    }
    @Test fun textTransformsLikeAnyLayer() {
        val d = withText()
        val flipped = ok(LayerManager.flip(d, "t", true)).layer("t")!!.transform
        assertTrue(flipped.flipH)
        val scaled = ok(LayerManager.setTransform(d, "t", LayerTransform(300f, 200f, 2f, 2f, 45f))).layer("t")!!.transform
        assertEquals(2f, scaled.scaleX, 0f); assertEquals(45f, scaled.rotation, 0f)
        assertEquals(LayerTransform(200f, 75f), ok(LayerManager.resetTransform(ok(LayerManager.setTransform(d, "t", LayerTransform(5f, 5f, 3f, 3f, 10f))), "t")).layer("t")!!.transform)
    }

    // ---- shape layers ----
    private fun withShape(type: ShapeType = ShapeType.Rectangle, spec: ShapeSpec = ShapeSpec(type = type), d: CanvasDocument = doc(), id: String = "s") =
        ok(LayerManager.addShapeLayer(d, id, type.label, spec, ShapeGeometry.defaultBounds(type, Pt(500f, 250f), 200f, spec.strokeWidth)))

    @Test fun shapeLayerHasNoBitmapAndCorrectBounds() {
        val d = withShape(ShapeType.Ellipse)
        val l = d.layer("s")!!
        assertEquals(LayerType.Shape, l.type); assertNull(l.content.resource)
        assertEquals(200f, l.width, 0f); assertEquals(130f, l.height, 0.001f); assertEquals(500f, l.transform.x, 0f)
    }
    @Test fun editingShapeStyleKeepsTypeAndGeometry() {
        val d = withShape(ShapeType.RoundedRectangle, ShapeSpec(type = ShapeType.RoundedRectangle, cornerRadius = 20f))
        val e = ok(LayerManager.updateShape(d, "s", ShapeSpec(type = ShapeType.Star, fillColor = 0x80112233.toInt(), strokeWidth = 12f, cornerRadius = 50f)))
        assertEquals(ShapeType.RoundedRectangle, shape(e, "s").type) // kind is fixed
        assertEquals(0x80112233.toInt(), shape(e, "s").fillColor); assertEquals(12f, shape(e, "s").strokeWidth, 0f)
        assertEquals(200f, e.layer("s")!!.width, 0f)
    }
    @Test fun lineBoxFollowsStrokeWidth() {
        val d = withShape(ShapeType.Line, ShapeSpec(type = ShapeType.Line, strokeWidth = 8f))
        assertEquals(12f, d.layer("s")!!.height, 0f)
        val thick = ok(LayerManager.updateShape(d, "s", shape(d, "s").copy(strokeWidth = 40f)))
        assertEquals(40f, thick.layer("s")!!.height, 0f); assertEquals(500f, thick.layer("s")!!.transform.x, 0f) // centre unchanged
    }
    @Test fun lockedShapeCannotBeEdited() {
        val d = ok(LayerManager.setLocked(withShape(), "s", true))
        assertNotNull(LayerManager.updateShape(d, "s", ShapeSpec(strokeWidth = 5f)).error)
        assertNotNull(LayerManager.setTransform(d, "s", LayerTransform(1f, 1f)).error)
    }
    @Test fun duplicatedShapeIsIndependent() {
        val d = ok(LayerManager.duplicate(withShape(ShapeType.Star, ShapeSpec(type = ShapeType.Star, strokeWidth = 6f, sides = 7)), "s", "s2"))
        assertEquals(listOf("s", "s2"), d.layers.map { it.id }); assertEquals(shape(d, "s"), shape(d, "s2"))
        val e = ok(LayerManager.updateShape(d, "s2", shape(d, "s2").copy(fillColor = 0xFF00FF00.toInt())))
        assertNotEquals(shape(e, "s").fillColor, shape(e, "s2").fillColor)
        assertEquals(d.layer("s")!!.transform, e.layer("s")!!.transform)
    }
    @Test fun dragCreationUpdatesBoundsAndResetTarget() {
        val d = withShape()
        val b = ShapeGeometry.fromDrag(ShapeType.Rectangle, Pt(100f, 100f), Pt(400f, 300f), 0f)
        val e = ok(LayerManager.setShapeBounds(d, "s", b)).layer("s")!!
        assertEquals(300f, e.width, 0.001f); assertEquals(200f, e.height, 0.001f); assertEquals(250f, e.transform.x, 0.001f)
        assertEquals(e.transform, e.defaultTransform)
        assertNotNull(LayerManager.setShapeBounds(withText(), "t", b).error)
    }

    // ---- mixed stacks ----
    @Test fun mixedStackKeepsOrderAndIndependence() {
        var d = ok(LayerManager.addPaintLayer(doc(), "p", "Paint"))
        d = withShape(d = d, id = "s"); d = withText(d, "t")
        assertEquals(listOf("p", "s", "t"), d.layers.map { it.id })
        val sBefore = d.layer("s")!!; val pBefore = d.layer("p")!!
        d = ok(LayerManager.updateText(d, "t", TextSpec(text = "New"), TextSize(10f, 10f)))
        d = ok(LayerManager.setOpacity(d, "t", 0.3f))
        assertEquals(sBefore, d.layer("s")); assertEquals(pBefore, d.layer("p"))
        d = ok(LayerManager.move(d, "t", MoveDirection.Bottom))
        assertEquals(listOf("t", "p", "s"), d.layers.map { it.id })
        d = ok(LayerManager.setVisible(d, "s", false))
        assertEquals(3, d.layers.size); assertFalse(d.layer("s")!!.visible)
        assertEquals("p", ok(LayerManager.delete(ok(LayerManager.select(d, "s")), "s")).selectedLayerId) // falls back to the layer below
    }
    @Test fun cropAndResizeKeepTextAndShapesVector() {
        var d = withText(); d = withShape(ShapeType.RoundedRectangle, ShapeSpec(type = ShapeType.RoundedRectangle, strokeWidth = 10f, cornerRadius = 40f), d, "s")
        val cropped = DocumentOps.crop(d, 100, 50, 600, 300).document
        assertEquals(100f, cropped.layer("t")!!.transform.x, 0.001f) // 200 - 100
        assertEquals(400f, cropped.layer("s")!!.transform.x, 0.001f) // 500 - 100
        assertEquals(shape(d, "s"), shape(cropped, "s")) // nothing about the shape changed
        val big = DocumentOps.resizeImage(d, ProjectMath.withPixelSize(d.config, 2000, 1000)).document
        assertEquals(20f, shape(big, "s").strokeWidth, 0.001f); assertEquals(80f, shape(big, "s").cornerRadius, 0.001f)
        assertEquals(2f, big.layer("t")!!.transform.scaleX, 0.001f); assertEquals("Hello", spec(big, "t").text)
        assertNull(big.layer("t")!!.content.resource)
    }
}
