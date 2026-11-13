package dev.videoeditor.timeline.command.internal

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.endUs

/** The range-cut algorithm of design section 3.12, shared by ApplyCuts and RippleDeleteRange. */
internal object RangeCut {
    /** Sort, merge overlapping or touching ranges, clamp to [0, limitUs) and drop empty ones. */
    fun normalize(
        ranges: List<TimeRange>,
        limitUs: Long,
    ): List<TimeRange> {
        val merged = mutableListOf<TimeRange>()
        for (r in ranges.sortedBy { it.startUs }) {
            val last = merged.lastOrNull()
            if (last != null && r.startUs <= last.endUs) {
                merged[merged.size - 1] = TimeRange(last.startUs, maxOf(last.endUs, r.endUs))
            } else {
                merged += r
            }
        }
        return merged
            .map { TimeRange(it.startUs.coerceIn(0, limitUs), it.endUs.coerceIn(0, limitUs)) }
            .filter { it.endUs > it.startUs }
    }

    /** Total length of [cuts] (normalized) inside [0, t). */
    fun removedBefore(
        cuts: List<TimeRange>,
        t: Long,
    ): Long {
        var sum = 0L
        for (c in cuts) {
            if (c.startUs >= t) break
            sum += minOf(c.endUs, t) - c.startUs
        }
        return sum
    }

    /** [start, end) minus the cuts, as maximal intervals in order. */
    fun pieces(
        startUs: Long,
        endUs: Long,
        cuts: List<TimeRange>,
    ): List<TimeRange> {
        val out = mutableListOf<TimeRange>()
        var cursor = startUs
        for (c in cuts) {
            if (c.endUs <= cursor) continue
            if (c.startUs >= endUs) break
            if (c.startUs > cursor) out += TimeRange(cursor, c.startUs)
            cursor = maxOf(cursor, c.endUs)
            if (cursor >= endUs) break
        }
        if (cursor < endUs) out += TimeRange(cursor, endUs)
        return out
    }

    class Result(
        val project: Project,
        val createdIds: List<String>,
        val droppedFragments: Int,
    )

    fun apply(
        project: Project,
        cuts: List<TimeRange>,
        trackIds: Set<String>?,
    ): Result {
        val ids = IdAllocator(project.nextId)
        val created = mutableListOf<String>()
        var dropped = 0
        val tracks =
            project.tracks.map { track ->
                if (trackIds != null && track.id !in trackIds) return@map track
                val clips = mutableListOf<Clip>()
                for (c in track.clips) {
                    val all = pieces(c.startUs, c.endUs, cuts)
                    val kept = all.filter { it.durationUs >= Limits.MIN_CLIP_DURATION_US }
                    dropped += all.size - kept.size
                    kept.forEachIndexed { i, p ->
                        val id =
                            if (i == 0) {
                                c.id
                            } else {
                                ids.alloc("clip").also { created += it }
                            }
                        clips += piece(c, p, id, p.startUs - removedBefore(cuts, p.startUs))
                    }
                }
                Track(track.id, track.kind, track.name, clips)
            }
        return Result(project.copy(tracks = tracks).withIds(ids), created, dropped)
    }

    private fun piece(
        c: Clip,
        p: TimeRange,
        id: String,
        newStart: Long,
    ): Clip =
        when (c) {
            is MediaClip ->
                c.copy(
                    id = id,
                    startUs = newStart,
                    durationUs = p.durationUs,
                    sourceInUs = c.sourceInUs + (p.startUs - c.startUs) * c.speedCenti / 100,
                )
            is CaptionClip -> c.copy(id = id, startUs = newStart, durationUs = p.durationUs)
        }
}
