package dev.videoeditor.timeline.query

import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.endUs

/** Pure helpers the UI uses to clamp drags before issuing commands (design section 3.15). */
object EditLimits {
    private const val MIN = Limits.MIN_CLIP_DURATION_US
    private const val MAX = Limits.MAX_TIMELINE_US

    /** Inclusive range of `toUs` values [dev.videoeditor.timeline.command.TrimClip] accepts, or null if the clip is unknown. */
    fun trimRange(
        project: Project,
        clipId: String,
        edge: ClipEdge,
        ripple: Boolean = false,
    ): LongRange? {
        val clip = project.clip(clipId) ?: return null
        val track = project.trackOf(clipId) ?: return null
        val s = clip.startUs
        val e = clip.endUs
        val followers = track.clips.filter { it.id != clip.id && it.startUs >= e }
        val lastFollowerEnd = followers.maxOfOrNull { it.endUs }
        val media = clip as? MediaClip
        val asset = media?.let { project.asset(it.assetId) }
        return when (edge) {
            ClipEdge.END -> {
                var hi = MAX
                if (media != null && asset != null) {
                    val avail = asset.durationUs - media.sourceInUs
                    hi = minOf(hi, s + ((avail + 1) * 100 - 1) / media.speedCenti)
                }
                if (ripple) {
                    if (lastFollowerEnd != null) hi = minOf(hi, e + (MAX - lastFollowerEnd))
                } else {
                    followers.minOfOrNull { it.startUs }?.let { hi = minOf(hi, it) }
                }
                (s + MIN)..hi
            }
            ClipEdge.START -> {
                val hi = e - MIN
                var lo = 0L
                if (media != null) lo = maxOf(lo, s - Math.floorDiv(media.sourceInUs * 100, media.speedCenti.toLong()))
                if (ripple) {
                    // The start stays put; the clip and its followers grow / shift right by s - toUs.
                    lo = maxOf(lo, s - (MAX - e))
                    if (lastFollowerEnd != null) lo = maxOf(lo, s - (MAX - lastFollowerEnd))
                } else {
                    track.clips.filter { it.id != clip.id && it.endUs <= s }.maxOfOrNull { it.endUs }?.let {
                        lo =
                            maxOf(lo, it)
                    }
                }
                lo..hi
            }
        }
    }

    /** Whether [startUs, startUs + durationUs) is a legal, free range on the track, ignoring [ignoreClipIds]. */
    fun canPlace(
        project: Project,
        trackId: String,
        startUs: Long,
        durationUs: Long,
        ignoreClipIds: Set<String> = emptySet(),
    ): Boolean {
        val track = project.track(trackId) ?: return false
        if (startUs < 0 || durationUs <= 0 || startUs + durationUs > MAX) return false
        val end = startUs + durationUs
        return track.clips.none { it.id !in ignoreClipIds && it.startUs < end && startUs < it.endUs }
    }

    /** The gap containing [timeUs], or null if a clip covers it or the track is unknown. */
    fun freeRangeAround(
        project: Project,
        trackId: String,
        timeUs: Long,
        ignoreClipIds: Set<String> = emptySet(),
    ): TimeRange? {
        val track = project.track(trackId) ?: return null
        if (timeUs < 0 || timeUs >= MAX) return null
        val clips = track.clips.filter { it.id !in ignoreClipIds }
        if (clips.any { it.startUs <= timeUs && timeUs < it.endUs }) return null
        val lo = clips.filter { it.endUs <= timeUs }.maxOfOrNull { it.endUs } ?: 0L
        val hi = clips.filter { it.startUs > timeUs }.minOfOrNull { it.startUs } ?: MAX
        return TimeRange(lo, hi)
    }
}
