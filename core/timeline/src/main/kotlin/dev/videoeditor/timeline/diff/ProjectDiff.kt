package dev.videoeditor.timeline.diff

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.range
import kotlinx.serialization.Serializable

@Serializable
enum class ClipField { TRACK, START, DURATION, SOURCE_IN, SPEED, VOLUME, TRANSFORM, TEXT, STYLE }

@Serializable
data class ClipSnapshot(
    val clipId: String,
    val trackId: String,
    val range: TimeRange,
    val sourceRange: TimeRange? = null,
    val label: String,
)

@Serializable
data class ClipChange(
    val before: ClipSnapshot,
    val after: ClipSnapshot,
    val changedFields: Set<ClipField>,
)

/** Entity-by-ID difference between two projects (design section 5.5). */
@Serializable
data class ProjectDiff(
    val clipsAdded: List<ClipSnapshot>,
    val clipsRemoved: List<ClipSnapshot>,
    val clipsChanged: List<ClipChange>,
    val tracksAdded: List<String>,
    val tracksRemoved: List<String>,
    val assetsAdded: List<String>,
    val settingsChanged: Boolean,
    val durationBeforeUs: Long,
    val durationAfterUs: Long,
) {
    val isEmpty: Boolean
        get() =
            clipsAdded.isEmpty() &&
                clipsRemoved.isEmpty() &&
                clipsChanged.isEmpty() &&
                tracksAdded.isEmpty() &&
                tracksRemoved.isEmpty() &&
                assetsAdded.isEmpty() &&
                !settingsChanged

    companion object {
        fun between(
            before: Project,
            after: Project,
        ): ProjectDiff {
            val b = located(before)
            val a = located(after)
            val added = a.filter { it.key !in b }.values.map { snapshot(after, it) }
            val removed = b.filter { it.key !in a }.values.map { snapshot(before, it) }
            val changed =
                a.values.mapNotNull { na ->
                    val ob = b[na.clip.id] ?: return@mapNotNull null
                    val fields = fields(ob, na)
                    if (fields.isEmpty()) null else ClipChange(snapshot(before, ob), snapshot(after, na), fields)
                }
            val beforeTracks = before.tracks.map { it.id }.toSet()
            val afterTracks = after.tracks.map { it.id }.toSet()
            val beforeAssets = before.assets.map { it.id }.toSet()
            return ProjectDiff(
                clipsAdded = added,
                clipsRemoved = removed,
                clipsChanged = changed,
                tracksAdded = after.tracks.map { it.id }.filter { it !in beforeTracks },
                tracksRemoved = before.tracks.map { it.id }.filter { it !in afterTracks },
                assetsAdded = after.assets.map { it.id }.filter { it !in beforeAssets },
                settingsChanged = before.output != after.output || before.name != after.name,
                durationBeforeUs = before.durationUs,
                durationAfterUs = after.durationUs,
            )
        }

        private class Located(
            val clip: Clip,
            val trackId: String,
        )

        /** Clips by id, in track order then start order (iteration order of a LinkedHashMap). */
        private fun located(p: Project): Map<String, Located> {
            val m = LinkedHashMap<String, Located>()
            for (t in p.tracks) for (c in t.clips) m[c.id] = Located(c, t.id)
            return m
        }

        private fun snapshot(
            p: Project,
            l: Located,
        ): ClipSnapshot {
            val c = l.clip
            return when (c) {
                is MediaClip ->
                    ClipSnapshot(
                        c.id,
                        l.trackId,
                        c.range,
                        TimeRange(c.sourceInUs, c.sourceOutUs),
                        p.asset(c.assetId)?.displayName ?: c.assetId,
                    )
                is CaptionClip -> ClipSnapshot(c.id, l.trackId, c.range, null, c.text.take(60))
            }
        }

        private fun fields(
            o: Located,
            n: Located,
        ): Set<ClipField> {
            val f = LinkedHashSet<ClipField>()
            val a = o.clip
            val b = n.clip
            if (o.trackId != n.trackId) f += ClipField.TRACK
            if (a.startUs != b.startUs) f += ClipField.START
            if (a.durationUs != b.durationUs) f += ClipField.DURATION
            if (a.transform != b.transform) f += ClipField.TRANSFORM
            if (a is MediaClip && b is MediaClip) {
                if (a.sourceInUs != b.sourceInUs) f += ClipField.SOURCE_IN
                if (a.speed != b.speed) f += ClipField.SPEED
                if (a.volume != b.volume) f += ClipField.VOLUME
            }
            if (a is CaptionClip && b is CaptionClip) {
                if (a.text != b.text) f += ClipField.TEXT
                if (a.style != b.style) f += ClipField.STYLE
            }
            return f
        }
    }
}
