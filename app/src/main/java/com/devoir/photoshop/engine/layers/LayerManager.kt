package com.devoir.photoshop.engine.layers

import com.devoir.photoshop.engine.BACKGROUND_ID
import com.devoir.photoshop.engine.BackgroundLayer
import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.ImagePlacement
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.transform.TransformGeometry
import com.devoir.photoshop.engine.transform.TransformMath
import com.devoir.photoshop.model.ImportedImageInfo
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** [error] != null means the operation was refused and [document] is the unchanged input. */
data class LayerResult(val document: CanvasDocument, val error: String? = null)

/**
 * All layer rules in one place (selection, lock, protection of the background, z-order,
 * resource ownership). Pure functions over the immutable document; no UI code.
 * Resource rule: a layer owns one reference to its bitmap; add/duplicate retain, delete releases.
 */
object LayerManager {
    const val MAX_NAME_LENGTH = 60
    const val MIN_SCALE = TransformMath.MIN_SCALE
    const val MAX_SCALE = TransformMath.MAX_SCALE

    private fun reject(doc: CanvasDocument, message: String) = LayerResult(doc, message)

    private fun CanvasDocument.mapLayer(id: String, block: (Layer) -> Layer) =
        copy(layers = layers.map { if (it.id == id) block(it) else it })

    fun addImageLayer(
        doc: CanvasDocument, id: String, name: String,
        resource: BitmapResource, info: ImportedImageInfo, placement: ImagePlacement,
    ): LayerResult {
        val layer = Layer(
            id = id, name = name, type = LayerType.Image,
            content = LayerContent.Image(resource, info),
            width = placement.width, height = placement.height,
            transform = LayerTransform(placement.left + placement.width / 2f, placement.top + placement.height / 2f),
        )
        return LayerResult(doc.copy(layers = doc.layers + layer, selectedLayerId = id))
    }

    private fun newPaintLayer(doc: CanvasDocument, id: String, name: String) = Layer(
        id = id, name = name, type = LayerType.Paint, content = LayerContent.Paint(),
        width = doc.width.toFloat(), height = doc.height.toFloat(),
        transform = LayerTransform(doc.width / 2f, doc.height / 2f),
    )

    fun addPaintLayer(doc: CanvasDocument, id: String, name: String): LayerResult =
        LayerResult(doc.copy(layers = doc.layers + newPaintLayer(doc, id, name), selectedLayerId = id))

    /** Inserts directly above [aboveId]; the background id (or an unknown id) inserts at the bottom. */
    fun addPaintLayerAbove(doc: CanvasDocument, id: String, name: String, aboveId: String): LayerResult {
        val idx = doc.layers.indexOfFirst { it.id == aboveId }
        val list = doc.layers.toMutableList().also { it.add(idx + 1, newPaintLayer(doc, id, name)) }
        return LayerResult(doc.copy(layers = list, selectedLayerId = id))
    }

    /** Gives an (empty) paint layer its pixel buffer. */
    fun attachPaintSurface(doc: CanvasDocument, id: String, resource: BitmapResource): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Layer not found")
        if (layer.content !is LayerContent.Paint) return reject(doc, "Not a paint layer")
        return LayerResult(doc.mapLayer(id) { it.copy(content = LayerContent.Paint(resource, 0)) })
    }

    /** Called after a stroke is committed so thumbnails refresh. */
    fun bumpRevision(doc: CanvasDocument, id: String): LayerResult {
        val layer = doc.layer(id) ?: return LayerResult(doc)
        val c = layer.content as? LayerContent.Paint ?: return LayerResult(doc)
        return LayerResult(doc.mapLayer(id) { it.copy(content = c.copy(revision = c.revision + 1)) })
    }

    fun select(doc: CanvasDocument, id: String): LayerResult =
        if (id == BACKGROUND_ID || doc.layer(id) != null) LayerResult(doc.copy(selectedLayerId = id))
        else reject(doc, "Layer not found")

    fun delete(doc: CanvasDocument, id: String): LayerResult {
        if (id == BACKGROUND_ID) return reject(doc, "The background layer can't be deleted")
        val idx = doc.layers.indexOfFirst { it.id == id }
        if (idx < 0) return reject(doc, "Layer not found")
        val layer = doc.layers[idx]
        if (layer.locked) return reject(doc, "Unlock the layer before deleting it")
        // pixels are NOT released here: undo may bring this layer back (see ResourceLedger)
        val remaining = doc.layers.toMutableList().also { it.removeAt(idx) }
        val selection = when {
            doc.selectedLayerId != id -> doc.selectedLayerId
            idx > 0 -> remaining[idx - 1].id // the layer below
            else -> BACKGROUND_ID
        }
        return LayerResult(doc.copy(layers = remaining, selectedLayerId = selection))
    }

    fun duplicate(doc: CanvasDocument, id: String, newId: String): LayerResult {
        val idx = doc.layers.indexOfFirst { it.id == id }
        if (idx < 0) return reject(doc, if (id == BACKGROUND_ID) "The background can't be duplicated" else "Layer not found")
        val src = doc.layers[idx]
        // Image pixels are shared (immutable); paint pixels are copied so strokes stay independent.
        val content = try {
            src.content.duplicate()
        } catch (e: OutOfMemoryError) {
            return reject(doc, "Not enough memory to duplicate this layer")
        }
        val copy = src.copy(
            id = newId,
            name = "${src.name} Copy".take(MAX_NAME_LENGTH),
            locked = false,
            content = content,
        )
        val list = doc.layers.toMutableList().also { it.add(idx + 1, copy) }
        return LayerResult(doc.copy(layers = list, selectedLayerId = newId))
    }

    fun rename(doc: CanvasDocument, id: String, name: String): LayerResult {
        if (id == BACKGROUND_ID) return reject(doc, "The background can't be renamed")
        if (doc.layer(id) == null) return reject(doc, "Layer not found")
        val clean = name.trim()
        if (clean.isEmpty()) return reject(doc, "Layer name can't be empty")
        return LayerResult(doc.mapLayer(id) { it.copy(name = clean.take(MAX_NAME_LENGTH)) })
    }

    fun setVisible(doc: CanvasDocument, id: String, visible: Boolean): LayerResult = when {
        id == BACKGROUND_ID -> LayerResult(doc.copy(background = BackgroundLayer(visible)))
        doc.layer(id) == null -> reject(doc, "Layer not found")
        else -> LayerResult(doc.mapLayer(id) { it.copy(visible = visible) })
    }

    fun setOpacity(doc: CanvasDocument, id: String, opacity: Float): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select a layer to change its opacity")
        if (layer.locked) return reject(doc, "Layer is locked")
        return LayerResult(doc.mapLayer(id) { it.copy(opacity = opacity.coerceIn(0f, 1f)) })
    }

    fun setLocked(doc: CanvasDocument, id: String, locked: Boolean): LayerResult {
        if (id == BACKGROUND_ID) return reject(doc, "The background is always locked")
        if (doc.layer(id) == null) return reject(doc, "Layer not found")
        return LayerResult(doc.mapLayer(id) { it.copy(locked = locked) })
    }

    fun move(doc: CanvasDocument, id: String, direction: MoveDirection): LayerResult {
        if (id == BACKGROUND_ID) return reject(doc, "The background always stays at the bottom")
        val idx = doc.layers.indexOfFirst { it.id == id }
        if (idx < 0) return reject(doc, "Layer not found")
        val last = doc.layers.lastIndex
        val target = when (direction) {
            MoveDirection.Up -> min(idx + 1, last)
            MoveDirection.Down -> max(idx - 1, 0)
            MoveDirection.Top -> last
            MoveDirection.Bottom -> 0
        }
        if (target == idx) return LayerResult(doc)
        val list = doc.layers.toMutableList()
        list.add(target, list.removeAt(idx))
        return LayerResult(doc.copy(layers = list))
    }

    fun setTransform(doc: CanvasDocument, id: String, t: LayerTransform): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select a layer to transform it")
        if (layer.locked) return reject(doc, "Layer is locked")
        val safe = LayerTransform(
            x = t.x.takeIf { it.isFinite() }?.coerceIn(0f, doc.width.toFloat()) ?: layer.transform.x,
            y = t.y.takeIf { it.isFinite() }?.coerceIn(0f, doc.height.toFloat()) ?: layer.transform.y,
            scaleX = t.scaleX.takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 1f,
            scaleY = t.scaleY.takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 1f,
            rotation = TransformMath.normalizeDegrees(t.rotation),
            flipH = t.flipH, flipV = t.flipV,
        )
        return LayerResult(doc.mapLayer(id) { it.copy(transform = safe) })
    }

    /** Mirrors the layer as seen on screen (the angle is mirrored too). Pixels are untouched. */
    fun flip(doc: CanvasDocument, id: String, horizontal: Boolean): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select a layer to flip it")
        if (layer.locked) return reject(doc, "Layer is locked")
        val t = layer.transform
        val mirrored = TransformMath.normalizeDegrees(-t.rotation)
        val n = if (horizontal) t.copy(flipH = !t.flipH, rotation = mirrored) else t.copy(flipV = !t.flipV, rotation = mirrored)
        return LayerResult(doc.mapLayer(id) { it.copy(transform = n) })
    }

    fun resetTransform(doc: CanvasDocument, id: String): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select a layer to reset it")
        if (layer.locked) return reject(doc, "Layer is locked")
        return LayerResult(doc.mapLayer(id) { it.copy(transform = it.defaultTransform) })
    }

    fun translate(doc: CanvasDocument, id: String, dx: Float, dy: Float): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select a layer to move it")
        return setTransform(doc, id, layer.transform.copy(x = layer.transform.x + dx, y = layer.transform.y + dy))
    }

    /** Adjustments are a property of image layers only; text, shapes and paint are never touched by them. */
    fun setAdjustments(doc: CanvasDocument, id: String, state: AdjustmentState): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Select an image layer to adjust it")
        val content = layer.content as? LayerContent.Image
            ?: return reject(doc, "Adjustments and filters apply to image layers only")
        if (layer.locked) return reject(doc, "Layer is locked — unlock it to adjust it")
        val s = state.sanitized()
        if (s == content.adjustments) return LayerResult(doc)
        return LayerResult(doc.mapLayer(id) { it.copy(content = content.copy(adjustments = s)) })
    }

    // ------------------------------------------------------------------ text & shapes

    fun autoTextName(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(24) ?: "Text"

    /** [left]/[top] = where the text box's top-left corner goes. [size] comes from the text layout. */
    fun addTextLayer(doc: CanvasDocument, id: String, spec: TextSpec, size: TextSize, left: Float, top: Float): LayerResult {
        if (spec.text.isBlank()) return reject(doc, "Text can't be empty")
        val layer = Layer(
            id = id, name = autoTextName(spec.text), type = LayerType.Text, content = LayerContent.Text(spec),
            width = size.width, height = size.height,
            transform = LayerTransform(
                (left + size.width / 2f).coerceIn(0f, doc.width.toFloat()),
                (top + size.height / 2f).coerceIn(0f, doc.height.toFloat()),
            ),
        )
        return LayerResult(doc.copy(layers = doc.layers + layer, selectedLayerId = id))
    }

    /** Edits the text in place (same layer, same id). The top-left corner stays where it was. */
    fun updateText(doc: CanvasDocument, id: String, spec: TextSpec, size: TextSize): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Layer not found")
        val old = (layer.content as? LayerContent.Text)?.spec ?: return reject(doc, "Not a text layer")
        if (layer.locked) return reject(doc, "Layer is locked — unlock it to edit the text")
        if (spec.text.isBlank()) return reject(doc, "Text can't be empty")
        val t = layer.transform
        val half = TransformGeometry.halfSize(layer)
        val topLeft = TransformGeometry.boxToDoc(layer, -half.x, -half.y)
        val hw = size.width * t.scaleX / 2f
        val hh = size.height * t.scaleY / 2f
        val rad = Math.toRadians(t.rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val moved = t.copy(x = topLeft.x + hw * c - hh * s, y = topLeft.y + hw * s + hh * c)
        // an automatic name follows the text; a name the user chose is kept
        val name = if (layer.name == autoTextName(old.text)) autoTextName(spec.text) else layer.name
        return LayerResult(doc.mapLayer(id) {
            it.copy(content = LayerContent.Text(spec), width = size.width, height = size.height, transform = moved, name = name)
        })
    }

    fun addShapeLayer(doc: CanvasDocument, id: String, name: String, spec: ShapeSpec, b: ShapeBounds): LayerResult {
        val t = LayerTransform(
            b.cx.coerceIn(0f, doc.width.toFloat()), b.cy.coerceIn(0f, doc.height.toFloat()),
            rotation = TransformMath.normalizeDegrees(b.rotation),
        )
        val layer = Layer(
            id = id, name = name, type = LayerType.Shape, content = LayerContent.Shape(spec),
            width = b.width.coerceAtLeast(ShapeGeometry.MIN_SIDE), height = b.height.coerceAtLeast(ShapeGeometry.MIN_SIDE),
            transform = t,
        )
        return LayerResult(doc.copy(layers = doc.layers + layer, selectedLayerId = id))
    }

    /** Used while a shape is being dragged out: replaces its geometry (also the reset target). */
    fun setShapeBounds(doc: CanvasDocument, id: String, b: ShapeBounds): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Layer not found")
        if (layer.content !is LayerContent.Shape) return reject(doc, "Not a shape layer")
        val t = LayerTransform(
            b.cx.coerceIn(0f, doc.width.toFloat()), b.cy.coerceIn(0f, doc.height.toFloat()),
            rotation = TransformMath.normalizeDegrees(b.rotation),
        )
        return LayerResult(doc.mapLayer(id) {
            it.copy(
                width = b.width.coerceAtLeast(ShapeGeometry.MIN_SIDE), height = b.height.coerceAtLeast(ShapeGeometry.MIN_SIDE),
                transform = t, defaultTransform = t,
            )
        })
    }

    fun updateShape(doc: CanvasDocument, id: String, spec: ShapeSpec): LayerResult {
        val layer = doc.layer(id) ?: return reject(doc, "Layer not found")
        val old = (layer.content as? LayerContent.Shape)?.spec ?: return reject(doc, "Not a shape layer")
        if (layer.locked) return reject(doc, "Layer is locked — unlock it to edit the shape")
        val fixed = spec.copy(type = old.type) // the shape kind is fixed once created
        val height = if (old.type == ShapeType.Line) ShapeGeometry.lineBoxHeight(fixed.strokeWidth) else layer.height
        return LayerResult(doc.mapLayer(id) { it.copy(content = LayerContent.Shape(fixed), height = height) })
    }

    fun releaseAll(doc: CanvasDocument) = doc.layers.forEach { it.content.release() }
}
