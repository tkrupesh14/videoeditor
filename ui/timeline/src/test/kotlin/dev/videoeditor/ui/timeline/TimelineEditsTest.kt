package dev.videoeditor.ui.timeline

import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.CommandExecutor
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.query.SnapKind
import dev.videoeditor.timeline.sample.SampleProjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEditsTest {
    private val base = SampleProjects.twoClipDemo() // clip_4 0-4s, clip_5 5-9s (video), clip_6 0.5-2.5s caption

    @Test
    fun moveSnapsToNeighbourEdgeAndApplies() {
        val edit = TimelineEdits.move(base, setOf("clip_5"), -950_000, "track_2", null, thresholdUs = 100_000)!!
        assertEquals(4_000_000L, edit.command.toStartUs)
        assertNull(edit.command.toTrackId)
        assertEquals(SnapKind.CLIP_END, edit.snappedTo?.kind)
        val r = CommandExecutor.execute(base, edit.command) as CommandResult.Success
        assertEquals(4_000_000L, r.project.clip("clip_5")!!.startUs)
    }

    @Test
    fun moveWithoutSnappingUsesRawDelta() {
        val edit = TimelineEdits.move(base, setOf("clip_5"), -950_000, null, null, thresholdUs = 0)!!
        assertEquals(4_050_000L, edit.command.toStartUs)
        assertNull(edit.snappedTo)
    }

    @Test
    fun moveSnapsToPlayheadAndNeverBeforeZero() {
        val edit =
            TimelineEdits.move(
                base,
                setOf("clip_5"),
                1_030_000,
                null,
                playheadUs = 6_000_000,
                thresholdUs = 100_000,
            )!!
        assertEquals(6_000_000L, edit.command.toStartUs)
        assertEquals(SnapKind.PLAYHEAD, edit.snappedTo?.kind)
        val clampedLeft = TimelineEdits.move(base, setOf("clip_4"), -9_000_000, null, null, thresholdUs = 0)!!
        assertEquals(0L, clampedLeft.command.toStartUs)
    }

    @Test
    fun moveToAnotherTrackOnlyWhenItDiffers() {
        assertEquals(
            "track_3",
            TimelineEdits.move(base, setOf("clip_5"), 0, "track_3", null, 0)!!.command.toTrackId,
        )
        assertNull(TimelineEdits.move(base, setOf("clip_5", "clip_6"), 0, "track_3", null, 0)!!.command.toTrackId)
        assertNull(TimelineEdits.move(base, setOf("nope"), 0, null, null, 0))
    }

    @Test
    fun trimSnapsThenClampsToEditLimits() {
        val snapped = TimelineEdits.trim(base, "clip_4", ClipEdge.END, 950_000, null, 100_000)!!
        assertEquals(5_000_000L, snapped.command.toUs)
        assertEquals(SnapKind.CLIP_START, snapped.snappedTo?.kind)
        val clamped = TimelineEdits.trim(base, "clip_4", ClipEdge.END, 3_000_000, null, 100_000)!!
        assertEquals(5_000_000L, clamped.command.toUs) // cannot run into clip_5
        assertNull(clamped.snappedTo)
        assertTrue(CommandExecutor.execute(base, clamped.command) is CommandResult.Success)
    }

    @Test
    fun trimPrefersPlayheadOnTies() {
        val t =
            TimelineEdits.trim(
                base,
                "clip_5",
                ClipEdge.START,
                -900_000,
                playheadUs = 4_200_000,
                thresholdUs = 100_000,
            )!!
        assertEquals(4_200_000L, t.command.toUs)
        assertEquals(SnapKind.PLAYHEAD, t.snappedTo?.kind)
    }

    @Test
    fun splitAtPlayheadUsesSelectionOrEverythingUnderIt() {
        assertEquals(listOf("clip_4", "clip_6"), TimelineEdits.splitAtPlayhead(base, emptySet(), 2_000_000)!!.clipIds)
        assertEquals(listOf("clip_4"), TimelineEdits.splitAtPlayhead(base, setOf("clip_4"), 2_000_000)!!.clipIds)
        assertNull(TimelineEdits.splitAtPlayhead(base, emptySet(), 4_500_000))
        assertNull(TimelineEdits.splitAtPlayhead(base, emptySet(), 0))
    }

    @Test
    fun deleteSelection() {
        assertNull(TimelineEdits.deleteSelection(emptySet()))
        assertEquals(DeleteClips(listOf("clip_4", "clip_5")), TimelineEdits.deleteSelection(setOf("clip_5", "clip_4")))
    }
}
