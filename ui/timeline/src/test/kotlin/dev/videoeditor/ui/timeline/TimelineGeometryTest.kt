package dev.videoeditor.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGeometryTest {
    @Test
    fun usAndPxRoundTripAtZoomAndScroll() {
        val g = TimelineGeometry(pxPerSecond = 100f, scrollUs = 0)
        assertEquals(100f, g.usToX(1_000_000), 0.001f)
        assertEquals(1_000_000L, g.xToUs(100f))
        val scrolled = TimelineGeometry(pxPerSecond = 250f, scrollUs = 2_000_000)
        assertEquals(0f, scrolled.usToX(2_000_000), 0.001f)
        assertEquals(-250f, scrolled.usToX(1_000_000), 0.001f)
        assertEquals(2_400_000L, scrolled.xToUs(100f))
        assertEquals(400_000L, scrolled.pxToDurationUs(100f))
        assertEquals(100f, scrolled.durationToPx(400_000), 0.001f)
    }

    @Test
    fun zoomAroundKeepsFocusTimeFixed() {
        val (z, s) = TimelineZoom.zoomAround(100f, 0, 2f, focusX = 300f, density = 2f, projectDurationUs = 60_000_000)
        assertEquals(200f, z, 0.001f)
        // Before: 300px at 200px/s -> 1.5s. After: 300px at 400px/s = 0.75s, so the left edge is at 0.75s.
        assertEquals(750_000L, s)
        assertEquals(300f, TimelineGeometry(z * 2f, s).usToX(1_500_000), 0.01f)
    }

    @Test
    fun zoomAndScrollAreClamped() {
        assertEquals(TimelineZoom.MAX_DP_PER_SECOND, TimelineZoom.clampZoom(1e6f), 0f)
        assertEquals(TimelineZoom.MIN_DP_PER_SECOND, TimelineZoom.clampZoom(0.0001f), 0f)
        assertEquals(0L, TimelineZoom.clampScroll(-5, 10_000_000))
        assertEquals(10_000_000L + TimelineZoom.TAIL_US, TimelineZoom.clampScroll(Long.MAX_VALUE, 10_000_000))
    }

    @Test
    fun rulerStepKeepsLabelsApart() {
        assertEquals(1_000_000L, TimelineZoom.rulerStepUs(pxPerSecond = 100f, minSpacingPx = 72f))
        assertEquals(100_000L, TimelineZoom.rulerStepUs(pxPerSecond = 1000f, minSpacingPx = 72f))
        assertEquals(60_000_000L, TimelineZoom.rulerStepUs(pxPerSecond = 2f, minSpacingPx = 72f))
    }

    @Test
    fun formatsTime() {
        assertEquals("1:05", TimelineZoom.formatTime(65_000_000))
        assertEquals("0:02.5", TimelineZoom.formatTime(2_500_000, stepUs = 500_000))
    }
}
