package com.devoir.photoshop.engine.history

import com.devoir.photoshop.engine.layers.BitmapResource

/**
 * Who keeps which bitmap alive. The current document counts as one holder and every history entry as
 * another; a bitmap is released exactly once, when the last holder lets go. That is what lets undo
 * bring back a deleted layer with its pixels intact, and what frees them when the entry is trimmed.
 * Main-thread only.
 */
class ResourceLedger {
    private val counts = HashMap<BitmapResource, Int>() // BitmapResource has identity equality

    fun hold(resources: Collection<BitmapResource>) {
        for (r in resources) counts[r] = (counts[r] ?: 0) + 1
    }

    fun drop(resources: Collection<BitmapResource>) {
        for (r in resources) {
            val left = (counts[r] ?: continue) - 1
            if (left <= 0) { counts.remove(r); r.release() } else counts[r] = left
        }
    }

    fun bytes(): Long = counts.keys.filter { !it.isReleased }.sumOf { it.byteCount }

    fun releaseAll() {
        counts.keys.toList().forEach { it.release() }
        counts.clear()
    }
}

/** What the UI needs to show: no engine objects, just values. */
data class HistoryUiState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    /** Oldest first. Entry i is the state after action i+1; state 0 is the start. */
    val labels: List<String> = emptyList(),
    /** Number of applied entries: 0 = start state, labels.size = newest state. */
    val currentIndex: Int = 0,
    /** True when the current state differs from the last [HistoryManager.markSaved] point. */
    val isModified: Boolean = false,
)

/**
 * Linear undo/redo with a redo branch that is discarded by any new action.
 *  - Command based: entries hold small immutable values or sparse pixel patches, never whole-project copies.
 *  - Bounded: at most [maxEntries] entries and [maxPatchBytes] of pixel backups; the oldest go first.
 *  - Merging: consecutive edits with the same key within [MERGE_WINDOW_MS] become one entry (slider drags),
 *    and a group (begin/endGroup) makes a whole gesture one entry however long it takes.
 *  - Save point: [markSaved] / [HistoryUiState.isModified] for project saving.
 */
class HistoryManager(
    private val ledger: ResourceLedger,
    var maxEntries: Int = DEFAULT_MAX_ENTRIES,
    var maxPatchBytes: Long = DEFAULT_MAX_PATCH_BYTES,
) {
    private class Entry(
        val command: HistoryCommand,
        var label: String,
        var resources: Set<BitmapResource>,
        var mergeKey: String?,
        var time: Long,
        var version: Long,
    ) {
        var sealed = false
    }

    private val undoStack = ArrayList<Entry>()
    private val redoStack = ArrayList<Entry>()
    private var versionCounter = 0L
    private var savedVersion = 0L
    private var groupKey: String? = null
    private var groupLabel: String? = null
    private var groupCounter = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val patchBytes: Long get() = undoStack.sumOf { it.command.patchBytes } + redoStack.sumOf { it.command.patchBytes }
    private val currentVersion: Long get() = undoStack.lastOrNull()?.version ?: 0L

    fun beginGroup(label: String) {
        undoStack.lastOrNull()?.sealed = true
        groupKey = "group:${++groupCounter}"
        groupLabel = label
    }

    /** Seals the group. A group whose net effect is nothing (e.g. a shape drag that was cancelled) leaves no entry. */
    fun endGroup() {
        val key = groupKey ?: return
        groupKey = null
        groupLabel = null
        val top = undoStack.lastOrNull() ?: return
        if (top.mergeKey != key) return
        top.sealed = true
        top.mergeKey = null
        val c = top.command
        if (c is DocumentStateCommand && c.before == c.after) {
            undoStack.removeAt(undoStack.lastIndex)
            dropEntry(top)
        }
    }

    /** Call BEFORE the new document replaces the old one, so the old document's bitmaps are still held. */
    fun record(command: HistoryCommand, mergeKey: String?, now: Long) {
        if (redoStack.isNotEmpty()) { // a new action ends the redo branch
            redoStack.forEach { dropEntry(it) }
            redoStack.clear()
        }
        val key = groupKey ?: mergeKey
        val top = undoStack.lastOrNull()
        if (key != null && top != null && top.mergeKey == key && !top.sealed &&
            (groupKey != null || now - top.time <= MERGE_WINDOW_MS) &&
            top.command is DocumentStateCommand && command is DocumentStateCommand
        ) {
            (top.command as DocumentStateCommand).after = command.after
            val merged = top.command.resources
            ledger.hold(merged)
            ledger.drop(top.resources)
            top.resources = merged
            top.time = now
            top.version = ++versionCounter
            return
        }
        val resources = command.resources
        ledger.hold(resources)
        undoStack.add(Entry(command, groupLabel ?: command.label, resources, key, now, ++versionCounter))
        trim()
    }

    /** Returns an error message, or null. On failure nothing has changed. */
    fun undo(ctx: HistoryContext): String? {
        val e = undoStack.lastOrNull() ?: return null
        try {
            e.command.undo(ctx)
        } catch (t: Throwable) {
            return "Couldn't undo \"${e.label}\": ${t.message ?: "unexpected error"}"
        }
        undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(e)
        e.sealed = true
        undoStack.lastOrNull()?.sealed = true // never merge new edits into an older entry after navigating
        return null
    }

    fun redo(ctx: HistoryContext): String? {
        val e = redoStack.lastOrNull() ?: return null
        try {
            e.command.redo(ctx)
        } catch (t: Throwable) {
            return "Couldn't redo \"${e.label}\": ${t.message ?: "unexpected error"}"
        }
        redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(e)
        e.sealed = true
        return null
    }

    /** Moves to the state after [index] actions (0 = start). Stops at the first failure. */
    fun jumpTo(index: Int, ctx: HistoryContext): String? {
        val target = index.coerceIn(0, undoStack.size + redoStack.size)
        while (undoStack.size > target) { undo(ctx)?.let { return it } }
        while (undoStack.size < target) { redo(ctx)?.let { return it } }
        return null
    }

    /** Frees memory by forgetting the oldest entry. Returns false when there is nothing left to drop. */
    fun dropOldest(): Boolean {
        val e = when {
            undoStack.isNotEmpty() -> undoStack.removeAt(0)
            redoStack.isNotEmpty() -> redoStack.removeAt(0)
            else -> return false
        }
        dropEntry(e)
        return true
    }

    fun clear() {
        groupKey = null
        groupLabel = null
        (undoStack + redoStack).forEach { dropEntry(it) }
        undoStack.clear()
        redoStack.clear()
    }

    fun markSaved() { savedVersion = currentVersion }

    /** The current state is not on disk (e.g. a recovered session): [HistoryUiState.isModified] stays true. */
    fun markUnsaved() { savedVersion = Long.MIN_VALUE }

    fun snapshot() = HistoryUiState(
        canUndo = canUndo,
        canRedo = canRedo,
        undoLabel = undoStack.lastOrNull()?.label,
        redoLabel = redoStack.lastOrNull()?.label,
        labels = undoStack.map { it.label } + redoStack.asReversed().map { it.label },
        currentIndex = undoStack.size,
        isModified = currentVersion != savedVersion,
    )

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > maxEntries || patchBytes > maxPatchBytes)) {
            dropEntry(undoStack.removeAt(0))
        }
    }

    private fun dropEntry(e: Entry) {
        ledger.drop(e.resources)
        e.command.release()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val DEFAULT_MAX_PATCH_BYTES = 96L * 1024 * 1024
        const val MERGE_WINDOW_MS = 700L
    }
}
