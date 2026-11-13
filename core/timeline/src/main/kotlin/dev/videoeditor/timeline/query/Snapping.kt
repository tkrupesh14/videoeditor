package dev.videoeditor.timeline.query

import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.endUs

enum class SnapKind { PLAYHEAD, MARKER, CLIP_START, CLIP_END, TIMELINE_START }

data class SnapPoint(
    val timeUs: Long,
    val kind: SnapKind,
    val clipId: String? = null,
)

data class SnapResult(
    val timeUs: Long,
    val snappedTo: SnapPoint?,
)

/** Snap helpers for drags (design section 3.15). Pure functions. */
object Snapping {
    fun candidates(
        project: Project,
        playheadUs: Long? = null,
        markersUs: List<Long> = emptyList(),
        excludeClipIds: Set<String> = emptySet(),
        trackIds: Set<String>? = null,
    ): List<SnapPoint> =
        buildList {
            add(SnapPoint(0, SnapKind.TIMELINE_START))
            playheadUs?.let { add(SnapPoint(it, SnapKind.PLAYHEAD)) }
            markersUs.forEach { add(SnapPoint(it, SnapKind.MARKER)) }
            for (t in project.tracks) {
                if (trackIds != null && t.id !in trackIds) continue
                for (c in t.clips) {
                    if (c.id in excludeClipIds) continue
                    add(SnapPoint(c.startUs, SnapKind.CLIP_START, c.id))
                    add(SnapPoint(c.endUs, SnapKind.CLIP_END, c.id))
                }
            }
        }

    private fun priority(kind: SnapKind): Int =
        when (kind) {
            SnapKind.PLAYHEAD -> 0
            SnapKind.MARKER -> 1
            SnapKind.CLIP_START, SnapKind.CLIP_END -> 2
            SnapKind.TIMELINE_START -> 3
        }

    private fun best(
        timeUs: Long,
        candidates: List<SnapPoint>,
        thresholdUs: Long,
    ): SnapPoint? =
        candidates
            .filter { Math.abs(it.timeUs - timeUs) <= thresholdUs }
            .minWithOrNull(compareBy<SnapPoint>({ Math.abs(it.timeUs - timeUs) }, { priority(it.kind) }, { it.timeUs }))

    /** The closest candidate within [thresholdUs], else [timeUs] unchanged. */
    fun snap(
        timeUs: Long,
        candidates: List<SnapPoint>,
        thresholdUs: Long,
    ): SnapResult {
        val hit = best(timeUs, candidates, thresholdUs) ?: return SnapResult(timeUs, null)
        return SnapResult(hit.timeUs, hit)
    }

    /** Snaps a dragged clip by either edge; returns the adjusted start (never below 0). */
    fun snapMove(
        startUs: Long,
        durationUs: Long,
        candidates: List<SnapPoint>,
        thresholdUs: Long,
    ): SnapResult {
        val s = best(startUs, candidates, thresholdUs)
        val e = best(startUs + durationUs, candidates, thresholdUs)
        val ds = s?.let { Math.abs(it.timeUs - startUs) }
        val de = e?.let { Math.abs(it.timeUs - (startUs + durationUs)) }
        return when {
            s == null && e == null -> SnapResult(maxOf(0L, startUs), null)
            e == null || (ds != null && ds <= de!!) -> SnapResult(maxOf(0L, s!!.timeUs), s)
            else -> SnapResult(maxOf(0L, e.timeUs - durationUs), e)
        }
    }
}
