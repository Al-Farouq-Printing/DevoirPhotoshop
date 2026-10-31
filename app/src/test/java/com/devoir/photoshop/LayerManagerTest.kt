package com.devoir.photoshop

import com.devoir.photoshop.engine.BACKGROUND_ID
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.LayerManager
import com.devoir.photoshop.engine.layers.LayerResult
import com.devoir.photoshop.engine.layers.LayerTransform
import com.devoir.photoshop.engine.layers.MoveDirection
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test

/** Uses paint layers only (no Android Bitmap needed), so it runs as a plain JVM test. */
class LayerManagerTest {
    private fun doc3(): CanvasDocument {
        var d = CanvasDocument(ProjectConfig(1000, 500))
        listOf("a", "b", "c").forEach { d = LayerManager.addPaintLayer(d, it, it.uppercase()).document }
        return d
    }
    private fun ids(d: CanvasDocument) = d.layers.map { it.id }
    private fun ok(r: LayerResult): CanvasDocument { assertNull(r.error); return r.document }

    @Test fun addSelectsNewLayerOnTop() {
        val d = doc3()
        assertEquals(listOf("a", "b", "c"), ids(d)); assertEquals("c", d.selectedLayerId)
        assertEquals(500f, d.layers[0].transform.x, 0.001f)
    }
    @Test fun reorder() {
        val d = doc3()
        assertEquals(listOf("b", "a", "c"), ids(ok(LayerManager.move(d, "a", MoveDirection.Up))))
        assertEquals(listOf("c", "a", "b"), ids(ok(LayerManager.move(d, "c", MoveDirection.Bottom))))
        assertEquals(listOf("b", "c", "a"), ids(ok(LayerManager.move(d, "a", MoveDirection.Top))))
        assertEquals(listOf("a", "c", "b"), ids(ok(LayerManager.move(d, "c", MoveDirection.Down))))
        assertSame(d, ok(LayerManager.move(d, "c", MoveDirection.Top)).let { d }) // no-op at limit is not an error
    }
    @Test fun deleteSelectsLayerBelow() {
        val d = ok(LayerManager.select(doc3(), "b"))
        val after = ok(LayerManager.delete(d, "b"))
        assertEquals(listOf("a", "c"), ids(after)); assertEquals("a", after.selectedLayerId)
        assertEquals(BACKGROUND_ID, ok(LayerManager.delete(after, "a")).selectedLayerId)
    }
    @Test fun deleteOtherLayerKeepsSelection() {
        val d = doc3() // "c" selected
        assertEquals("c", ok(LayerManager.delete(d, "a")).selectedLayerId)
    }
    @Test fun backgroundIsProtected() {
        val d = doc3()
        assertNotNull(LayerManager.delete(d, BACKGROUND_ID).error)
        assertNotNull(LayerManager.rename(d, BACKGROUND_ID, "x").error)
        assertNotNull(LayerManager.move(d, BACKGROUND_ID, MoveDirection.Up).error)
        assertNotNull(LayerManager.setLocked(d, BACKGROUND_ID, false).error)
    }
    @Test fun backgroundVisibilityToggles() {
        val d = ok(LayerManager.setVisible(doc3(), BACKGROUND_ID, false))
        assertFalse(d.background.visible)
    }
    @Test fun duplicateGoesAboveWithNewIdAndName() {
        val d = doc3()
        val r = ok(LayerManager.duplicate(d, "a", "a2"))
        assertEquals(listOf("a", "a2", "b", "c"), ids(r))
        assertEquals("A Copy", r.layer("a2")!!.name); assertEquals("a2", r.selectedLayerId)
        assertEquals(d.layer("a")!!.transform, r.layer("a2")!!.transform)
    }
    @Test fun renameValidation() {
        val d = doc3()
        assertNotNull(LayerManager.rename(d, "a", "   ").error)
        assertEquals("New", ok(LayerManager.rename(d, "a", "  New ")).layer("a")!!.name)
        assertEquals(LayerManager.MAX_NAME_LENGTH, ok(LayerManager.rename(d, "a", "x".repeat(200))).layer("a")!!.name.length)
    }
    @Test fun opacityIsClampedAndReal() {
        val d = doc3()
        assertEquals(0f, ok(LayerManager.setOpacity(d, "a", -3f)).layer("a")!!.opacity, 0f)
        assertEquals(0.4f, ok(LayerManager.setOpacity(d, "a", 0.4f)).layer("a")!!.opacity, 0f)
        assertEquals(1f, ok(LayerManager.setOpacity(d, "a", 9f)).layer("a")!!.opacity, 0f)
    }
    @Test fun visibilityKeepsLayer() {
        val d = ok(LayerManager.setVisible(doc3(), "b", false))
        assertEquals(3, d.layers.size); assertFalse(d.layer("b")!!.visible)
        assertTrue(ok(LayerManager.setVisible(d, "b", true)).layer("b")!!.visible)
    }
    @Test fun lockBlocksEdits() {
        val d = ok(LayerManager.setLocked(doc3(), "a", true))
        assertNotNull(LayerManager.setTransform(d, "a", LayerTransform(1f, 1f)).error)
        assertNotNull(LayerManager.translate(d, "a", 5f, 5f).error)
        assertNotNull(LayerManager.setOpacity(d, "a", 0.5f).error)
        assertNotNull(LayerManager.delete(d, "a").error)
        assertNull(LayerManager.setVisible(d, "a", false).error) // visibility is still allowed
        assertFalse(ok(LayerManager.duplicate(d, "a", "a2")).layer("a2")!!.locked)
    }
    @Test fun transformIsClamped() {
        val d = doc3()
        val t = ok(LayerManager.setTransform(d, "a", LayerTransform(5000f, -10f, 100f, 100f, 45f))).layer("a")!!.transform
        assertEquals(1000f, t.x, 0f); assertEquals(0f, t.y, 0f); assertEquals(LayerManager.MAX_SCALE, t.scaleX, 0f); assertEquals(LayerManager.MAX_SCALE, t.scaleY, 0f); assertEquals(45f, t.rotation, 0f)
    }
    @Test fun translateMovesCentre() {
        val t = ok(LayerManager.translate(doc3(), "a", 10f, -20f)).layer("a")!!.transform
        assertEquals(510f, t.x, 0.001f); assertEquals(230f, t.y, 0.001f)
    }
}
