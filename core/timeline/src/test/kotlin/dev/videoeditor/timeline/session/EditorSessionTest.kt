package dev.videoeditor.timeline.session

import dev.videoeditor.timeline.S
import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.diff.ClipField
import dev.videoeditor.timeline.fixture
import dev.videoeditor.timeline.media
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EditorSessionTest {
    private val base = fixture(video = listOf(media("clip_10", 0, 4 * S), media("clip_11", 5 * S, 2 * S)))

    @Test
    fun `undo redo round trip and labels`() {
        val s = EditorSession(base)
        s.execute(SplitClip(listOf("clip_10"), 2 * S))
        val afterSplit = s.project
        s.execute(DeleteClips(listOf("clip_100")))
        assertEquals("Deleted clip_100", s.state.undoLabel)
        assertEquals(2, s.revision)
        assertEquals("Deleted clip_100", s.undo()!!.label)
        assertEquals(afterSplit, s.project)
        assertEquals("Deleted clip_100", s.state.redoLabel)
        s.undo()
        assertEquals(base, s.project)
        assertNull(s.undo())
        s.redo()
        s.redo()
        assertNull(s.redo())
        assertEquals(6, s.revision)
        s.undo()
        assertTrue(s.state.canRedo)
        s.execute(MoveClip(listOf("clip_11"), 6 * S))
        assertFalse(s.state.canRedo, "a new command clears redo")
    }

    @Test
    fun `failed or unchanged command adds no entry and keeps redo`() {
        val s = EditorSession(base)
        s.execute(SplitClip(listOf("clip_10"), 2 * S))
        s.undo()
        val rev = s.revision
        assertInstanceOf(CommandResult.Failure::class.java, s.execute(SplitClip(listOf("clip_10"), 0)))
        val unchanged = s.execute(TrimClip("clip_10", ClipEdge.END, 4 * S)) as CommandResult.Success
        assertFalse(unchanged.changed)
        assertEquals(rev, s.revision)
        assertTrue(s.state.canRedo)
        assertTrue(s.undoHistory.isEmpty())
    }

    @Test
    fun `history limit drops the oldest`() {
        val s = EditorSession(base, historyLimit = 3)
        for (t in listOf(8L, 9L, 10L, 11L)) s.execute(MoveClip(listOf("clip_11"), t * S))
        assertEquals(3, s.undoHistory.size)
        assertEquals(
            8 * S,
            s.undoHistory
                .first()
                .before
                .clip("clip_11")!!
                .startUs,
        )
        repeat(3) { s.undo() }
        assertNull(s.undo())
        assertEquals(8 * S, s.project.clip("clip_11")!!.startUs)
    }

    @Test
    fun `batch label is the entry label`() {
        val s = EditorSession(base)
        s.execute(Batch(listOf(SplitClip(listOf("clip_10"), S)), label = "Tighten"))
        assertEquals("Tighten", s.state.undoLabel)
    }

    @Test
    fun `transaction coalesces a drag into one undo step`() {
        val s = EditorSession(base)
        assertTrue(s.beginTransaction("Trim clip"))
        assertFalse(s.beginTransaction("again"))
        s.updateTransaction(TrimClip("clip_10", ClipEdge.END, 3 * S))
        s.updateTransaction(TrimClip("clip_10", ClipEdge.END, 4_500_000))
        val failed = s.updateTransaction(TrimClip("clip_10", ClipEdge.END, 6 * S))
        assertInstanceOf(CommandResult.Failure::class.java, failed)
        assertEquals(4_500_000, s.project.clip("clip_10")!!.durationUs, "failed update keeps last good state")
        assertEquals(
            CommandResult.Failure(CommandError.TransactionInProgress),
            s.execute(SplitClip(listOf("clip_10"), S)),
        )
        assertNull(s.undo())
        val e = s.commitTransaction()!!
        assertEquals("Trim clip", e.label)
        assertEquals(base, e.before)
        assertEquals(1, s.undoHistory.size)
        s.undo()
        assertEquals(base, s.project)
    }

    @Test
    fun `cancel restores base, update without begin throws, no-op commit returns null`() {
        val s = EditorSession(base)
        assertThrows<IllegalStateException> { s.updateTransaction(TrimClip("clip_10", ClipEdge.END, 3 * S)) }
        assertThrows<IllegalStateException> { s.commitTransaction() }
        s.cancelTransaction()
        s.beginTransaction("Move")
        s.updateTransaction(MoveClip(listOf("clip_11"), 8 * S))
        s.cancelTransaction()
        assertEquals(base, s.project)
        assertTrue(s.undoHistory.isEmpty())
        s.beginTransaction("Move")
        s.updateTransaction(MoveClip(listOf("clip_11"), 8 * S))
        s.updateTransaction(MoveClip(listOf("clip_11"), 5 * S))
        assertNull(s.commitTransaction())
        assertTrue(s.undoHistory.isEmpty())
    }

    @Test
    fun `preview commit is one step and stale revision fails`() {
        val s = EditorSession(base)
        val pv = s.newPreview()
        val split = pv.submit(SplitClip(listOf("clip_10"), 2 * S)) as CommandResult.Success
        pv.submit(DeleteClips(split.createdIds))
        assertInstanceOf(CommandResult.Failure::class.java, pv.submit(DeleteClips(listOf("nope"))))
        assertEquals(base, s.project, "preview does not touch the session")
        assertEquals(2, pv.commands.size)
        val diff = pv.diff()
        assertEquals(setOf(ClipField.DURATION), diff.clipsChanged.single().changedFields)
        assertTrue(diff.clipsAdded.isEmpty())

        val stale = s.newPreview()
        stale.submit(SplitClip(listOf("clip_11"), 6 * S))
        val r = s.commitPreview(pv, "Remove tail")
        assertInstanceOf(CommandResult.Success::class.java, r)
        assertEquals(pv.project, s.project)
        assertEquals(1, s.undoHistory.size)
        assertEquals(Origin.AI, s.undoHistory.single().origin)
        assertEquals("Remove tail", (s.undoHistory.single().command as Batch).label)
        val before = s.project
        assertEquals(CommandResult.Failure(CommandError.StaleRevision(0, 1)), s.commitPreview(stale, "x"))
        assertEquals(before, s.project)
        s.undo()
        assertEquals(base, s.project)
    }

    @Test
    fun `empty preview commits as a no-op, transaction blocks commit`() {
        val s = EditorSession(base)
        val r = s.commitPreview(s.newPreview(), "nothing") as CommandResult.Success
        assertFalse(r.changed)
        assertTrue(s.undoHistory.isEmpty())
        val pv = s.newPreview()
        pv.submit(SplitClip(listOf("clip_10"), S))
        s.beginTransaction("drag")
        assertEquals(CommandResult.Failure(CommandError.TransactionInProgress), s.commitPreview(pv, "x"))
    }

    @Test
    fun `listeners get one state per change in order and load clears history`() {
        val s = EditorSession(base)
        val seen = mutableListOf<Long>()
        val handle = s.addListener { seen += it.revision }
        s.execute(SplitClip(listOf("clip_10"), S))
        s.execute(SplitClip(listOf("clip_10"), 0))
        s.undo()
        assertEquals(listOf(1L, 2L), seen)
        s.load(base)
        assertEquals(3L, seen.last())
        assertFalse(s.state.canRedo)
        handle.close()
        s.execute(SplitClip(listOf("clip_10"), S))
        assertEquals(3, seen.size)
        val target = s.asTarget(Origin.MCP)
        target.submit(DeleteClips(listOf("clip_11")))
        assertEquals(Origin.MCP, s.undoHistory.last().origin)
        assertNotNull(target.project)
    }
}
