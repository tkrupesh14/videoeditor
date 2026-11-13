package dev.videoeditor.timeline.query

import dev.videoeditor.timeline.S
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.fixture
import dev.videoeditor.timeline.media
import dev.videoeditor.timeline.model.TimeRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryTest {
    private val p =
        fixture(
            video =
                listOf(
                    media("clip_10", 1 * S, 2 * S, sourceIn = 5 * S),
                    media(
                        "clip_11",
                        4 * S,
                        2 * S,
                    ),
                ),
        )

    @Test
    fun `snap prefers closest, then playhead over clip edges`() {
        val c = Snapping.candidates(p, playheadUs = 3 * S, excludeClipIds = setOf("clip_11"))
        assertEquals(SnapKind.PLAYHEAD, Snapping.snap(3 * S + 10, c, 100).snappedTo!!.kind)
        assertEquals(1 * S, Snapping.snap(1 * S + 50, c, 100).timeUs)
        assertNull(Snapping.snap(2 * S, c, 100).snappedTo)
        assertEquals(2 * S, Snapping.snap(2 * S, c, 100).timeUs)
        // snapMove by the end edge: a 1 s clip starting at 1.99 s ends near the 3 s playhead
        assertEquals(2 * S, Snapping.snapMove(1_990_000, S, c, 20_000).timeUs)
        assertTrue(Snapping.candidates(p, trackIds = emptySet()).all { it.kind == SnapKind.TIMELINE_START })
    }

    @Test
    fun `edit limits`() {
        assertEquals((1 * S + 10_000)..(4 * S), EditLimits.trimRange(p, "clip_10", ClipEdge.END))
        assertEquals(0L..(3 * S - 10_000), EditLimits.trimRange(p, "clip_10", ClipEdge.START))
        assertNull(EditLimits.trimRange(p, "x", ClipEdge.END))
        assertTrue(EditLimits.canPlace(p, "track_3", 0, S))
        assertFalse(EditLimits.canPlace(p, "track_3", 0, S + 1))
        assertTrue(EditLimits.canPlace(p, "track_3", 0, 3 * S, setOf("clip_10")))
        assertEquals(TimeRange(3 * S, 4 * S), EditLimits.freeRangeAround(p, "track_3", 3 * S))
        assertNull(EditLimits.freeRangeAround(p, "track_3", 2 * S))
    }

    @Test
    fun `queries`() {
        assertEquals(listOf("clip_11"), Queries.listClips(p, fromUs = 3 * S).map { it.id })
        assertEquals(7 * S, Queries.listClips(p).first().sourceOutUs)
        assertEquals("clip_10", Queries.clipAt(p, "track_3", 1 * S)!!.id)
        assertTrue(Queries.clipsAt(p, 1 * S).isEmpty())
        assertEquals(3, Queries.summary(p).tracks.size)
    }
}
