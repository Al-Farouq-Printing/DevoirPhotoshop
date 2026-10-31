package com.devoir.photoshop

import com.devoir.photoshop.engine.CanvasDocument
import com.devoir.photoshop.engine.history.DocumentStateCommand
import com.devoir.photoshop.engine.history.HistoryCommand
import com.devoir.photoshop.engine.history.HistoryContext
import com.devoir.photoshop.engine.history.HistoryManager
import com.devoir.photoshop.engine.history.ResourceLedger
import com.devoir.photoshop.engine.layers.BitmapResource
import com.devoir.photoshop.model.ProjectConfig
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests of the history rules (commands are fakes or document-value commands; no Android objects). */
class HistoryManagerTest {
    private class Fake(
        override val label: String,
        val log: MutableList<String>,
        override val patchBytes: Long = 0L,
        var failUndo: Boolean = false,
    ) : HistoryCommand {
        var released = 0
        override val resources: Set<BitmapResource> = emptySet()
        override fun undo(ctx: HistoryContext) { if (failUndo) throw IllegalStateException("boom"); log += "undo $label" }
        override fun redo(ctx: HistoryContext) { log += "redo $label" }
        override fun release() { released++ }
    }

    private class Ctx : HistoryContext {
        var last: CanvasDocument? = null
        override fun install(doc: CanvasDocument) { last = doc }
    }

    private fun mgr(maxEntries: Int = 100, maxBytes: Long = Long.MAX_VALUE) = HistoryManager(ResourceLedger(), maxEntries, maxBytes)
    private fun doc(n: Int) = CanvasDocument(ProjectConfig(10 + n, 10))
    private fun change(label: String, from: Int, to: Int) = DocumentStateCommand(label, doc(from), doc(to))

    @Test fun undoRedoSequence() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        listOf("A", "B", "C").forEach { m.record(Fake(it, log), null, 0) }
        m.undo(ctx); m.undo(ctx); m.redo(ctx); m.redo(ctx)
        assertEquals(listOf("undo C", "undo B", "redo B", "redo C"), log)
        assertNull(m.redo(ctx)); assertEquals(4, log.size); assertFalse(m.canRedo)
        assertTrue(m.canUndo)
    }
    @Test fun nothingToUndoIsHarmless() {
        val m = mgr(); val ctx = Ctx()
        assertNull(m.undo(ctx)); assertNull(m.redo(ctx)); assertFalse(m.canUndo); assertFalse(m.canRedo)
    }
    @Test fun newActionClearsTheRedoBranch() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        val b = Fake("B", log)
        m.record(Fake("A", log), null, 0); m.record(b, null, 0); m.record(Fake("C", log), null, 0)
        m.undo(ctx) // C
        m.record(Fake("D", log), null, 0)
        assertFalse(m.canRedo)
        assertEquals(listOf("A", "B", "D"), m.snapshot().labels)
        assertEquals(0, b.released)
    }
    @Test fun discardedRedoEntriesAreReleased() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        val c = Fake("C", log)
        m.record(Fake("A", log), null, 0); m.record(c, null, 0)
        m.undo(ctx); m.record(Fake("D", log), null, 0)
        assertEquals(1, c.released)
    }
    @Test fun entryLimitKeepsTheNewestAndReleasesTheOldest() {
        val log = mutableListOf<String>(); val m = mgr(maxEntries = 3)
        val all = listOf("A", "B", "C", "D", "E").map { Fake(it, log) }
        all.forEach { m.record(it, null, 0) }
        assertEquals(listOf("C", "D", "E"), m.snapshot().labels)
        assertEquals(1, all[0].released); assertEquals(1, all[1].released); assertEquals(0, all[4].released)
    }
    @Test fun pixelBackupBudgetTrimsOldestButKeepsTheLatest() {
        val log = mutableListOf<String>(); val m = mgr(maxBytes = 100)
        listOf("A", "B", "C").forEach { m.record(Fake(it, log, patchBytes = 60), null, 0) }
        assertEquals(listOf("C"), m.snapshot().labels)
        assertEquals(60L, m.patchBytes)
    }
    @Test fun rapidChangesWithTheSameKeyMergeIntoOneEntry() {
        val m = mgr(); val ctx = Ctx()
        m.record(change("Layer opacity", 0, 1), "opacity:x", 0)
        m.record(change("Layer opacity", 1, 2), "opacity:x", 100)
        m.record(change("Layer opacity", 2, 3), "opacity:x", 200)
        assertEquals(1, m.snapshot().labels.size)
        m.undo(ctx); assertEquals(doc(0), ctx.last)
        m.redo(ctx); assertEquals(doc(3), ctx.last)
    }
    @Test fun slowOrDifferentChangesDoNotMerge() {
        val m = mgr()
        m.record(change("a", 0, 1), "k", 0); m.record(change("a", 1, 2), "k", HistoryManager.MERGE_WINDOW_MS + 1)
        assertEquals(2, m.snapshot().labels.size)
        val n = mgr()
        n.record(change("a", 0, 1), "k1", 0); n.record(change("b", 1, 2), "k2", 10)
        assertEquals(2, n.snapshot().labels.size)
    }
    @Test fun navigatingSealsEntriesSoLaterEditsStartANewOne() {
        val m = mgr(); val ctx = Ctx()
        m.record(change("a", 0, 1), "k", 0)
        m.undo(ctx); m.redo(ctx)
        m.record(change("a", 1, 2), "k", 10)
        assertEquals(2, m.snapshot().labels.size)
    }
    @Test fun aGroupIsOneEntryRegardlessOfDuration() {
        val m = mgr(); val ctx = Ctx()
        m.beginGroup("Move layer")
        m.record(change("x", 0, 1), null, 0); m.record(change("x", 1, 2), null, 9_999); m.record(change("x", 2, 3), null, 60_000)
        m.endGroup()
        assertEquals(listOf("Move layer"), m.snapshot().labels)
        m.record(change("y", 3, 4), null, 60_001)
        assertEquals(2, m.snapshot().labels.size)
        m.undo(ctx); assertEquals(doc(3), ctx.last)
        m.undo(ctx); assertEquals(doc(0), ctx.last)
    }
    @Test fun aGroupWithNoNetEffectLeavesNoEntry() {
        val m = mgr()
        m.beginGroup("Add shape")
        m.record(change("x", 0, 1), null, 0); m.record(change("x", 1, 0), null, 5)
        m.endGroup()
        assertFalse(m.canUndo)
    }
    @Test fun aFailedUndoChangesNothing() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        m.record(Fake("A", log, failUndo = true), null, 0)
        val error = m.undo(ctx)
        assertNotNull(error); assertTrue(error!!.contains("A"))
        assertTrue(m.canUndo); assertFalse(m.canRedo); assertTrue(log.isEmpty())
    }
    @Test fun jumpToAnyState() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        listOf("A", "B", "C").forEach { m.record(Fake(it, log), null, 0) }
        assertNull(m.jumpTo(1, ctx)); assertEquals(listOf("undo C", "undo B"), log); assertEquals(1, m.snapshot().currentIndex)
        assertNull(m.jumpTo(3, ctx)); assertEquals(listOf("undo C", "undo B", "redo B", "redo C"), log)
        assertNull(m.jumpTo(99, ctx)); assertEquals(4, log.size)
        assertNull(m.jumpTo(0, ctx)); assertEquals(0, m.snapshot().currentIndex)
    }
    @Test fun snapshotDescribesTheTimeline() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        listOf("A", "B", "C").forEach { m.record(Fake(it, log), null, 0) }
        m.undo(ctx)
        val s = m.snapshot()
        assertEquals(listOf("A", "B", "C"), s.labels); assertEquals(2, s.currentIndex)
        assertEquals("B", s.undoLabel); assertEquals("C", s.redoLabel); assertTrue(s.canUndo && s.canRedo)
    }
    @Test fun savePointTracksModification() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        assertFalse(m.snapshot().isModified)
        m.record(Fake("A", log), null, 0); assertTrue(m.snapshot().isModified)
        m.markSaved(); assertFalse(m.snapshot().isModified)
        m.record(Fake("B", log), null, 0); assertTrue(m.snapshot().isModified)
        m.undo(ctx); assertFalse(m.snapshot().isModified)
        m.redo(ctx); assertTrue(m.snapshot().isModified)
        m.undo(ctx); m.undo(ctx); assertTrue(m.snapshot().isModified)
    }
    @Test fun unsavedMarkKeepsTheDocumentDirty() {
        val m = mgr()
        m.markUnsaved(); assertTrue(m.snapshot().isModified)
        m.markSaved(); assertFalse(m.snapshot().isModified)
    }
    @Test fun mergedEditsAfterSavingCountAsModified() {
        val m = mgr()
        m.record(change("o", 0, 1), "k", 0); m.markSaved()
        m.record(change("o", 1, 2), "k", 10)
        assertTrue(m.snapshot().isModified)
    }
    @Test fun dropOldestReleasesAndEventuallyEmpties() {
        val log = mutableListOf<String>(); val m = mgr()
        val a = Fake("A", log); m.record(a, null, 0); m.record(Fake("B", log), null, 0)
        assertTrue(m.dropOldest()); assertEquals(1, a.released); assertEquals(listOf("B"), m.snapshot().labels)
        assertTrue(m.dropOldest()); assertFalse(m.dropOldest())
    }
    @Test fun clearReleasesEverything() {
        val log = mutableListOf<String>(); val m = mgr(); val ctx = Ctx()
        val all = listOf("A", "B", "C").map { Fake(it, log) }
        all.forEach { m.record(it, null, 0) }
        m.undo(ctx); m.clear()
        assertTrue(all.all { it.released == 1 }); assertFalse(m.canUndo); assertFalse(m.canRedo)
    }
    @Test fun limitCanBeChangedAtRuntime() {
        val log = mutableListOf<String>(); val m = mgr(maxEntries = 10)
        repeat(6) { m.record(Fake("e$it", log), null, 0) }
        m.maxEntries = 2
        m.record(Fake("last", log), null, 0)
        assertEquals(listOf("e5", "last"), m.snapshot().labels)
    }
}
