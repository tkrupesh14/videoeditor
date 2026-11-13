package dev.videoeditor.ui.timeline

import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.endUs
import dev.videoeditor.timeline.query.EditLimits
import dev.videoeditor.timeline.query.SnapPoint
import dev.videoeditor.timeline.query.Snapping

data class MoveEdit(
    val command: MoveClip,
    val snappedTo: SnapPoint?,
)

data class TrimEdit(
    val command: TrimClip,
    val snappedTo: SnapPoint?,
)

/**
 * Turns gestures into timeline commands. All drag maths is relative to the project as it was when the gesture
 * started ([base]) because `EditorSession.updateTransaction` re-applies each command to that base. Pure.
 */
object TimelineEdits {
    /**
     * Move [clipIds] as a rigid group by [deltaUs], snapping the group's start or end to the playhead and other clip
     * edges within [thresholdUs] (0 disables snapping). [targetTrackId] moves the group to another track when all
     * clips are on one track and the target differs.
     */
    fun move(
        base: Project,
        clipIds: Set<String>,
        deltaUs: Long,
        targetTrackId: String?,
        playheadUs: Long?,
        thresholdUs: Long,
    ): MoveEdit? {
        val clips = clipIds.mapNotNull { base.clip(it) }
        if (clips.isEmpty()) return null
        val groupStart = clips.minOf { it.startUs }
        val groupEnd = clips.maxOf { it.endUs }
        val raw = maxOf(0L, groupStart + deltaUs)
        val sourceTracks = clips.mapNotNull { base.trackOf(it.id)?.id }.toSet()
        val toTrack = targetTrackId?.takeIf { sourceTracks.size == 1 && it !in sourceTracks && base.track(it) != null }
        val snap =
            if (thresholdUs > 0) {
                val candidates = Snapping.candidates(base, playheadUs, excludeClipIds = clipIds)
                Snapping.snapMove(raw, groupEnd - groupStart, candidates, thresholdUs)
            } else {
                null
            }
        val start = snap?.timeUs ?: raw
        return MoveEdit(MoveClip(clipIds.toList(), toStartUs = start, toTrackId = toTrack), snap?.snappedTo)
    }

    /**
     * Move one edge of [clipId] by [deltaUs], snapping to the playhead and other clip edges, then clamping to the
     * range [EditLimits.trimRange] allows. Null if the clip is unknown.
     */
    fun trim(
        base: Project,
        clipId: String,
        edge: ClipEdge,
        deltaUs: Long,
        playheadUs: Long?,
        thresholdUs: Long,
    ): TrimEdit? {
        val clip = base.clip(clipId) ?: return null
        val limits = EditLimits.trimRange(base, clipId, edge) ?: return null
        val original = if (edge == ClipEdge.START) clip.startUs else clip.endUs
        val raw = original + deltaUs
        val snap =
            if (thresholdUs > 0) {
                Snapping.snap(raw, Snapping.candidates(base, playheadUs, excludeClipIds = setOf(clipId)), thresholdUs)
            } else {
                null
            }
        val wanted = snap?.timeUs ?: raw
        val clamped = wanted.coerceIn(limits.first, limits.last)
        val snappedTo = snap?.snappedTo?.takeIf { clamped == wanted }
        return TrimEdit(TrimClip(clipId, edge, clamped), snappedTo)
    }

    /**
     * Split at the playhead: the selected clips that strictly contain it, or every clip that does when nothing
     * selected contains it. Null when there is nothing to split.
     */
    fun splitAtPlayhead(
        project: Project,
        selection: Set<String>,
        playheadUs: Long,
    ): SplitClip? {
        val all = project.tracks.flatMap { it.clips }.filter { it.startUs < playheadUs && playheadUs < it.endUs }
        val selected = all.filter { it.id in selection }
        val ids = (selected.ifEmpty { all }).map { it.id }
        return if (ids.isEmpty()) null else SplitClip(ids, playheadUs)
    }

    fun deleteSelection(selection: Set<String>): DeleteClips? =
        if (selection.isEmpty()) null else DeleteClips(selection.sorted())
}
