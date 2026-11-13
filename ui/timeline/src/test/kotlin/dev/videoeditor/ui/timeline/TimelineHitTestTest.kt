package dev.videoeditor.ui.timeline

import dev.videoeditor.timeline.sample.SampleProjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineHitTestTest {
    // density 1: dp == px. Rows: 0 = captions (48..104), 1 = video (104..160).
    private val project = SampleProjects.twoClipDemo()
    private val metrics =
        TimelineMetrics(rulerHeightPx = 48f, trackHeightPx = 56f, handleHalfPx = 24f, snapThresholdPx = 12f)
    private val g = TimelineGeometry(pxPerSecond = 100f, scrollUs = 0)
    private val videoY = 130f

    private fun hit(
        x: Float,
        y: Float = videoY,
        geometry: TimelineGeometry = g,
    ) = TimelineHitTest.hitTest(project, geometry, metrics, x, y)

    @Test
    fun edgeHandlesAreAtLeast48dp() {
        assertTrue(metrics.handleHalfPx * 2 >= 48f)
    }

    @Test
    fun topLayerIsTheFirstRow() {
        assertEquals(listOf("track_3", "track_2"), TimelineHitTest.displayTracks(project).map { it.id })
        assertEquals("track_3", TimelineHitTest.trackAtY(project, metrics, 60f)?.id)
        assertEquals("track_2", TimelineHitTest.trackAtY(project, metrics, videoY)?.id)
    }

    @Test
    fun rulerAndBelowTracks() {
        assertEquals(TimelineHit.Ruler(1_000_000), hit(100f, y = 10f))
        assertEquals(TimelineHit.Empty(null, 1_000_000), hit(100f, y = 400f))
    }

    @Test
    fun bodyEdgesAndGap() {
        assertEquals(TimelineHit.OnClip("clip_4", "track_2", ClipPart.BODY, 2_000_000), hit(200f))
        assertEquals(ClipPart.END_EDGE, (hit(395f) as TimelineHit.OnClip).part)
        // Outside the clip but within the 24dp outer half of the handle.
        assertEquals(ClipPart.END_EDGE, (hit(420f) as TimelineHit.OnClip).part)
        assertEquals(TimelineHit.Empty("track_2", 4_500_000), hit(450f))
        val start5 = hit(480f) as TimelineHit.OnClip
        assertEquals("clip_5" to ClipPart.START_EDGE, start5.clipId to start5.part)
        assertEquals(ClipPart.START_EDGE, (hit(10f) as TimelineHit.OnClip).part)
    }

    @Test
    fun narrowClipKeepsAGrabbableBody() {
        val zoomedOut = TimelineGeometry(pxPerSecond = 10f, scrollUs = 0) // clip_4 is 0..40px
        assertEquals(ClipPart.BODY, (hit(20f, geometry = zoomedOut) as TimelineHit.OnClip).part)
        assertEquals(ClipPart.START_EDGE, (hit(5f, geometry = zoomedOut) as TimelineHit.OnClip).part)
        assertEquals(ClipPart.END_EDGE, (hit(36f, geometry = zoomedOut) as TimelineHit.OnClip).part)
    }

    @Test
    fun hitTestRespectsScroll() {
        val scrolled = TimelineGeometry(pxPerSecond = 100f, scrollUs = 5_000_000)
        val h = hit(200f, geometry = scrolled) as TimelineHit.OnClip
        assertEquals("clip_5", h.clipId)
        assertEquals(ClipPart.BODY, h.part)
        assertEquals(7_000_000L, h.timeUs)
    }
}
