package dev.videoeditor.ui.timeline

import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.endUs
import kotlin.math.abs

/**
 * Pixel sizes of the timeline rows. Built from dp by the composable; plain floats here so hit-testing is pure.
 *
 * @param handleHalfPx half the width of an edge-trim touch target; the target is centred on the clip edge, so
 *   `2 * handleHalfPx` should be at least 48dp.
 */
data class TimelineMetrics(
    val rulerHeightPx: Float,
    val trackHeightPx: Float,
    val handleHalfPx: Float,
    val snapThresholdPx: Float,
) {
    fun contentHeightPx(trackCount: Int): Float = rulerHeightPx + trackHeightPx * trackCount

    fun rowTopPx(row: Int): Float = rulerHeightPx + trackHeightPx * row
}

enum class ClipPart { BODY, START_EDGE, END_EDGE }

sealed interface TimelineHit {
    val timeUs: Long

    data class Ruler(
        override val timeUs: Long,
    ) : TimelineHit

    data class OnClip(
        val clipId: String,
        val trackId: String,
        val part: ClipPart,
        override val timeUs: Long,
    ) : TimelineHit

    /** Empty space; [trackId] is null below the last track. */
    data class Empty(
        val trackId: String?,
        override val timeUs: Long,
    ) : TimelineHit
}

object TimelineHitTest {
    /** Rows top to bottom: the top layer (last track) is drawn first, like most NLEs. */
    fun displayTracks(project: Project): List<Track> = project.tracks.asReversed()

    fun trackAtY(
        project: Project,
        metrics: TimelineMetrics,
        y: Float,
    ): Track? {
        if (y < metrics.rulerHeightPx) return null
        val row = ((y - metrics.rulerHeightPx) / metrics.trackHeightPx).toInt()
        return displayTracks(project).getOrNull(row)
    }

    fun hitTest(
        project: Project,
        geometry: TimelineGeometry,
        metrics: TimelineMetrics,
        x: Float,
        y: Float,
    ): TimelineHit {
        val timeUs = geometry.xToUs(x).coerceAtLeast(0L)
        if (y < metrics.rulerHeightPx) return TimelineHit.Ruler(timeUs)
        val track = trackAtY(project, metrics, y) ?: return TimelineHit.Empty(null, timeUs)

        // Edge handles: a touch target centred on each edge. Inside a narrow clip each handle gets at most a third
        // of the clip so the middle stays grabbable as the body. Nearest edge wins between adjacent clips.
        var best: TimelineHit.OnClip? = null
        var bestDist = Float.MAX_VALUE
        for (clip in track.clips) {
            val sx = geometry.usToX(clip.startUs)
            val ex = geometry.usToX(clip.endUs)
            val inner = minOf(metrics.handleHalfPx, (ex - sx) / 3f)
            val ds = abs(x - sx)
            if (x >= sx - metrics.handleHalfPx && x <= sx + inner && ds < bestDist) {
                best = TimelineHit.OnClip(clip.id, track.id, ClipPart.START_EDGE, timeUs)
                bestDist = ds
            }
            val de = abs(x - ex)
            if (x >= ex - inner && x <= ex + metrics.handleHalfPx && de < bestDist) {
                best = TimelineHit.OnClip(clip.id, track.id, ClipPart.END_EDGE, timeUs)
                bestDist = de
            }
        }
        best?.let { return it }
        val body = track.clips.firstOrNull { geometry.usToX(it.startUs) <= x && x < geometry.usToX(it.endUs) }
        return if (body != null) {
            TimelineHit.OnClip(body.id, track.id, ClipPart.BODY, timeUs)
        } else {
            TimelineHit.Empty(track.id, timeUs)
        }
    }

    /** Body-only hit (no edge zones), used for hover/context menus where trimming is irrelevant. */
    fun clipIdAt(
        project: Project,
        geometry: TimelineGeometry,
        metrics: TimelineMetrics,
        x: Float,
        y: Float,
    ): String? = (hitTest(project, geometry, metrics, x, y) as? TimelineHit.OnClip)?.clipId
}
