package com.devoir.photoshop.engine.history

import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.layers.BitmapResource

/** How a command puts a document back; implemented by the engine so history never touches rendering. */
interface HistoryContext {
    fun install(doc: CanvasDocument)
}

/**
 * One undoable action. Commands are small: they hold immutable document values (structural sharing,
 * so no layer or bitmap is copied) and, for brush/eraser, a sparse pixel patch.
 */
interface HistoryCommand {
    val label: String

    /** Bitmaps this command needs to stay alive (e.g. the pixels of a deleted layer). */
    val resources: Set<BitmapResource>

    /** Native memory held by this command's own pixel backups. */
    val patchBytes: Long

    /** May throw; must leave everything untouched if it does. */
    fun undo(ctx: HistoryContext)
    fun redo(ctx: HistoryContext)
    fun release()
}

object DocumentResources {
    fun of(doc: CanvasDocument): Set<BitmapResource> {
        val s = LinkedHashSet<BitmapResource>()
        for (layer in doc.layers) layer.content.resource?.let { s.add(it) }
        return s
    }
}

/**
 * Any change that is fully described by the document value: layers (add, delete, duplicate, rename,
 * order, visibility, lock, opacity), transforms, text, shapes, adjustments/filters, crop, canvas/image
 * resize. The documents hold canvas size, DPI, background, layer list, properties and selection.
 * Rapid changes of the same kind are merged by [HistoryManager] by extending [after].
 */
class DocumentStateCommand(
    override val label: String,
    val before: CanvasDocument,
    var after: CanvasDocument,
) : HistoryCommand {
    override val resources: Set<BitmapResource> get() = DocumentResources.of(before) + DocumentResources.of(after)
    override val patchBytes: Long get() = 0L
    override fun undo(ctx: HistoryContext) = ctx.install(before)
    override fun redo(ctx: HistoryContext) = ctx.install(after)
    override fun release() {}
}

/**
 * One brush or eraser stroke = one entry, however many touch events it had. Undo/redo swap the
 * affected 64 px tiles of the paint layer (see [PixelPatch]) and then install the document values,
 * which also covers a paint layer or pixel buffer created for this very stroke.
 */
class StrokeCommand(
    override val label: String,
    val before: CanvasDocument,
    val after: CanvasDocument,
    val patch: PixelPatch,
) : HistoryCommand {
    override val resources: Set<BitmapResource>
        get() = DocumentResources.of(before) + DocumentResources.of(after) + patch.resource
    override val patchBytes: Long get() = patch.bytes
    override fun undo(ctx: HistoryContext) { patch.swap(); ctx.install(before) }
    override fun redo(ctx: HistoryContext) { patch.swap(); ctx.install(after) }
    override fun release() = patch.release()
}
