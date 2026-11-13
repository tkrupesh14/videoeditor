package dev.videoeditor.ui.timeline

import kotlin.math.roundToLong

private const val US_PER_SECOND = 1_000_000.0

/**
 * Maps timeline time to horizontal pixels for one zoom level and scroll position. Pure; unit-tested.
 *
 * @param pxPerSecond horizontal pixels per second of timeline time (zoom, already multiplied by density).
 * @param scrollUs timeline time at the left edge of the viewport.
 */
data class TimelineGeometry(
    val pxPerSecond: Float,
    val scrollUs: Long,
) {
    init {
        require(pxPerSecond > 0f) { "pxPerSecond must be positive" }
    }

    fun usToX(us: Long): Float = ((us - scrollUs) / US_PER_SECOND * pxPerSecond).toFloat()

    fun xToUs(x: Float): Long = scrollUs + pxToDurationUs(x)

    fun pxToDurationUs(px: Float): Long = (px / pxPerSecond * US_PER_SECOND).roundToLong()

    fun durationToPx(durationUs: Long): Float = (durationUs / US_PER_SECOND * pxPerSecond).toFloat()
}

/** Zoom/scroll math for the timeline. Zoom is in dp per second so it is density independent. */
object TimelineZoom {
    const val MIN_DP_PER_SECOND = 2f
    const val MAX_DP_PER_SECOND = 1000f
    const val DEFAULT_DP_PER_SECOND = 60f

    /** Extra scrollable room after the end of the project. */
    const val TAIL_US = 5_000_000L

    fun clampZoom(dpPerSecond: Float): Float = dpPerSecond.coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)

    fun clampScroll(
        scrollUs: Long,
        projectDurationUs: Long,
    ): Long = scrollUs.coerceIn(0L, maxOf(0L, projectDurationUs + TAIL_US))

    /**
     * Applies [factor] to the zoom while keeping the time under [focusX] (pixels from the viewport's left edge)
     * fixed. Returns the new (dpPerSecond, scrollUs).
     */
    fun zoomAround(
        dpPerSecond: Float,
        scrollUs: Long,
        factor: Float,
        focusX: Float,
        density: Float,
        projectDurationUs: Long,
    ): Pair<Float, Long> {
        val newZoom = clampZoom(dpPerSecond * factor)
        val anchorUs = TimelineGeometry(dpPerSecond * density, scrollUs).xToUs(focusX)
        val newScroll = anchorUs - TimelineGeometry(newZoom * density, 0).pxToDurationUs(focusX)
        return newZoom to clampScroll(newScroll, projectDurationUs)
    }

    /** Ruler tick interval: the smallest "nice" step whose on-screen spacing is at least [minSpacingPx]. */
    fun rulerStepUs(
        pxPerSecond: Float,
        minSpacingPx: Float,
    ): Long = NICE_STEPS_US.firstOrNull { it / US_PER_SECOND * pxPerSecond >= minSpacingPx } ?: NICE_STEPS_US.last()

    private val NICE_STEPS_US =
        listOf(
            100_000L,
            250_000L,
            500_000L,
            1_000_000L,
            2_000_000L,
            5_000_000L,
            10_000_000L,
            15_000_000L,
            30_000_000L,
            60_000_000L,
            120_000_000L,
            300_000_000L,
            600_000_000L,
            1_800_000_000L,
        )

    /** `m:ss` or `m:ss.f` when the step is below one second. */
    fun formatTime(
        us: Long,
        stepUs: Long = 1_000_000L,
    ): String {
        val totalTenths = us / 100_000
        val totalSeconds = totalTenths / 10
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        val base = "$m:${s.toString().padStart(2, '0')}"
        return if (stepUs < 1_000_000L) "$base.${totalTenths % 10}" else base
    }
}
