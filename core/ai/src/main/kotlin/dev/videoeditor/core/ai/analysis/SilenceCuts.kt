package dev.videoeditor.core.ai.analysis

import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.session.EditPreview
import dev.videoeditor.timeline.session.EditorSession

/** Offline "remove silences": turns silence ranges into one [ApplyCuts] command. Pure, no AI, no network. */
object SilenceCuts {
    /** Cuts shorter than this are dropped (ApplyCuts itself drops fragments under 10 ms). */
    const val DEFAULT_MIN_CUT_US: Long = 10_000

    /**
     * Clamps [silences] (timeline time) to 0..[timelineDurationUs], merges overlapping or touching ranges,
     * drops ranges shorter than [minCutUs], and returns the command, or null when nothing is left to cut.
     * Ranges stay in pre-edit coordinates, which is what ApplyCuts expects.
     */
    fun toApplyCuts(
        silences: List<TimeRange>,
        timelineDurationUs: Long,
        minCutUs: Long = DEFAULT_MIN_CUT_US,
    ): ApplyCuts? {
        require(timelineDurationUs >= 0) { "timelineDurationUs must be >= 0" }
        require(minCutUs >= 0) { "minCutUs must be >= 0" }
        val clamped =
            silences
                .map { TimeRange(it.startUs.coerceIn(0, timelineDurationUs), it.endUs.coerceIn(0, timelineDurationUs)) }
                .filter { it.endUs > it.startUs }
                .sortedBy { it.startUs }
        val merged = mutableListOf<TimeRange>()
        for (r in clamped) {
            val last = merged.lastOrNull()
            if (last != null && r.startUs <= last.endUs) {
                merged[merged.lastIndex] = TimeRange(last.startUs, maxOf(last.endUs, r.endUs))
            } else {
                merged += r
            }
        }
        val kept = merged.filter { it.durationUs >= minCutUs }
        return if (kept.isEmpty()) null else ApplyCuts(kept)
    }

    /** Builds a preview on a copy of [session]'s project; the user accepts it with `commitPreview`. */
    fun preview(
        session: EditorSession,
        silences: List<TimeRange>,
        minCutUs: Long = DEFAULT_MIN_CUT_US,
    ): EditPreview {
        val preview = session.newPreview()
        toApplyCuts(silences, preview.project.durationUs, minCutUs)?.let { preview.submit(it) }
        return preview
    }
}
