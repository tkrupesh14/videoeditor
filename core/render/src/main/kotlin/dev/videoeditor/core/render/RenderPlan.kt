package dev.videoeditor.core.render

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.CaptionStyle
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.OutputSettings
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.Transform
import dev.videoeditor.timeline.model.endUs

/**
 * Pure, Android-free description of what to render for a [Project]. The Media3 adapter
 * ([CompositionMapper]) only translates this; every timing decision is made (and unit-tested) here.
 *
 * Time is in microseconds. Timeline positions are in output time; `clipStartUs`/`clipEndUs` are in the
 * asset's own (source) time.
 */
data class RenderPlan(
    val revisionKey: String,
    val output: OutputSettings,
    val durationUs: Long,
    /** Media tracks in project order (z-order: first = bottom), only tracks that contain at least one segment. */
    val tracks: List<RenderTrack>,
    /** Caption overlay spans from every track, sorted by start time. */
    val captions: List<CaptionSpan>,
    /** Things that could not be mapped faithfully (missing assets, overlaps); rendering still proceeds. */
    val issues: List<PlanIssue>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()

    /** First VIDEO track with real media, else first OVERLAY track; the "main" sequence for preview. */
    val mainVideoTrack: RenderTrack?
        get() =
            tracks.firstOrNull { it.kind == TrackKind.VIDEO && it.hasVideo }
                ?: tracks.firstOrNull { it.hasVideo }
}

data class RenderTrack(
    val trackId: String,
    val kind: TrackKind,
    /** Contiguous from timeline 0: gaps are explicit [RenderSegment.Gap] entries. */
    val segments: List<RenderSegment>,
) {
    val endUs: Long get() = segments.lastOrNull()?.timelineEndUs ?: 0L
    val hasVideo: Boolean get() = segments.any { it is RenderSegment.Media && it.useVideo }
    val hasAudio: Boolean get() = segments.any { it is RenderSegment.Media && it.useAudio }
    val mediaSegments: List<RenderSegment.Media> get() = segments.filterIsInstance<RenderSegment.Media>()

    /** The media segment covering timeline time [timeUs], or null in a gap / past the end. */
    fun mediaAt(timeUs: Long): RenderSegment.Media? {
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                timeUs < s.timelineStartUs -> hi = mid - 1
                timeUs >= s.timelineEndUs -> lo = mid + 1
                else -> return s as? RenderSegment.Media
            }
        }
        return null
    }
}

sealed interface RenderSegment {
    val timelineStartUs: Long
    val durationUs: Long
    val timelineEndUs: Long get() = timelineStartUs + durationUs

    /** Blank video / silence. */
    data class Gap(
        override val timelineStartUs: Long,
        override val durationUs: Long,
    ) : RenderSegment

    data class Media(
        val clipId: String,
        val assetId: String,
        val uri: String,
        override val timelineStartUs: Long,
        override val durationUs: Long,
        /** Source-time clipping start (inclusive). */
        val clipStartUs: Long,
        /** Source-time clipping end (exclusive). `clipEndUs - clipStartUs == durationUs * speed` (floored). */
        val clipEndUs: Long,
        /** Full (unclipped) asset duration; Media3 needs it on every EditedMediaItem for preview. */
        val assetDurationUs: Long,
        /** Display size of the source (after rotation metadata), when the probe knew it. */
        val sourceWidth: Int?,
        val sourceHeight: Int?,
        val speed: Double,
        val volume: Double,
        /** Whether this segment contributes picture (false on AUDIO tracks or audio-only assets). */
        val useVideo: Boolean,
        /** Whether this segment contributes sound (false when the asset has no audio or volume is 0). */
        val useAudio: Boolean,
        val transform: Transform,
    ) : RenderSegment {
        val sourceDurationUs: Long get() = clipEndUs - clipStartUs
    }
}

data class CaptionSpan(
    val clipId: String,
    val trackId: String,
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val style: CaptionStyle,
    val transform: Transform,
) {
    fun isActiveAt(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs
}

/** The caption visible at [timeUs] (the latest-starting one when spans overlap), or null. */
fun List<CaptionSpan>.activeAt(timeUs: Long): CaptionSpan? = lastOrNull { it.isActiveAt(timeUs) }

data class PlanIssue(
    val kind: Kind,
    val trackId: String,
    val clipId: String?,
    val message: String,
) {
    enum class Kind { MISSING_ASSET, OVERLAP, EMPTY_CLIP, CLIP_ON_WRONG_TRACK }
}

object RenderPlanner {
    fun plan(
        project: Project,
        revisionKey: String = project.hashCode().toString(),
    ): RenderPlan {
        val issues = mutableListOf<PlanIssue>()
        val tracks = mutableListOf<RenderTrack>()
        val captions = mutableListOf<CaptionSpan>()

        for (track in project.tracks) {
            val segments = mutableListOf<RenderSegment>()
            var cursor = 0L
            val sorted = track.clips.sortedWith(compareBy({ it.startUs }, { it.id }))
            for (clip in sorted) {
                when (clip) {
                    is CaptionClip -> {
                        if (clip.durationUs <= 0) {
                            issues += PlanIssue(PlanIssue.Kind.EMPTY_CLIP, track.id, clip.id, "Caption has no duration")
                        } else if (clip.text.isNotBlank()) {
                            captions +=
                                CaptionSpan(
                                    clipId = clip.id,
                                    trackId = track.id,
                                    startUs = clip.startUs,
                                    endUs = clip.endUs,
                                    text = clip.text,
                                    style = clip.style,
                                    transform = clip.transform,
                                )
                        }
                    }
                    is MediaClip -> {
                        if (track.kind == TrackKind.CAPTION) {
                            issues +=
                                PlanIssue(
                                    PlanIssue.Kind.CLIP_ON_WRONG_TRACK,
                                    track.id,
                                    clip.id,
                                    "Media clip on a caption track is not rendered",
                                )
                            continue
                        }
                        if (clip.durationUs <= 0) {
                            issues += PlanIssue(PlanIssue.Kind.EMPTY_CLIP, track.id, clip.id, "Clip has no duration")
                            continue
                        }
                        if (clip.startUs < cursor) {
                            issues +=
                                PlanIssue(
                                    PlanIssue.Kind.OVERLAP,
                                    track.id,
                                    clip.id,
                                    "Clip overlaps the previous clip on its track; skipped",
                                )
                            continue
                        }
                        val asset = project.asset(clip.assetId)
                        if (asset == null) {
                            issues +=
                                PlanIssue(
                                    PlanIssue.Kind.MISSING_ASSET,
                                    track.id,
                                    clip.id,
                                    "Asset ${clip.assetId} not found; rendered as a gap",
                                )
                            continue
                        }
                        val useVideo = asset.hasVideo && track.kind != TrackKind.AUDIO
                        val useAudio = asset.hasAudio && clip.volume > 0.0
                        if (clip.startUs > cursor) {
                            segments.addGap(cursor, clip.startUs - cursor)
                        }
                        cursor = clip.endUs
                        if (!useVideo && !useAudio) {
                            // Nothing audible or visible (e.g. muted audio-only clip): Media3 rejects an item with
                            // both streams removed, so it is a gap.
                            segments.addGap(clip.startUs, clip.durationUs)
                            continue
                        }
                        segments +=
                            RenderSegment.Media(
                                clipId = clip.id,
                                assetId = asset.id,
                                uri = asset.uri,
                                timelineStartUs = clip.startUs,
                                durationUs = clip.durationUs,
                                clipStartUs = clip.sourceInUs,
                                clipEndUs = clip.sourceOutUs,
                                assetDurationUs = asset.durationUs,
                                sourceWidth = asset.width,
                                sourceHeight = asset.height,
                                speed = clip.speedCenti / 100.0,
                                volume = clip.volume.coerceIn(0.0, Limits.MAX_VOLUME),
                                useVideo = useVideo,
                                useAudio = useAudio,
                                transform = clip.transform,
                            )
                    }
                }
            }
            if (segments.any { it is RenderSegment.Media }) {
                tracks += RenderTrack(track.id, track.kind, segments)
            }
        }

        val duration =
            maxOf(
                tracks.maxOfOrNull { it.endUs } ?: 0L,
                captions.maxOfOrNull { it.endUs } ?: 0L,
            )
        return RenderPlan(
            revisionKey = revisionKey,
            output = project.output,
            durationUs = duration,
            tracks = tracks,
            captions = captions.sortedWith(compareBy({ it.startUs }, { it.clipId })),
            issues = issues,
        )
    }

    /** Appends a gap, merging it into a directly preceding gap. */
    private fun MutableList<RenderSegment>.addGap(
        startUs: Long,
        durationUs: Long,
    ) {
        val last = lastOrNull()
        if (last is RenderSegment.Gap && last.timelineEndUs == startUs) {
            this[size - 1] = last.copy(durationUs = last.durationUs + durationUs)
        } else {
            add(RenderSegment.Gap(startUs, durationUs))
        }
    }
}
