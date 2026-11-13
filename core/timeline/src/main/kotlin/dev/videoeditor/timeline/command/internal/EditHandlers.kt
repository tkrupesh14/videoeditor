package dev.videoeditor.timeline.command.internal

import dev.videoeditor.timeline.command.AddCaption
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.EditCaption
import dev.videoeditor.timeline.command.RippleDeleteRange
import dev.videoeditor.timeline.command.SetClipProperties
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.endUs

private const val MIN = Limits.MIN_CLIP_DURATION_US

internal fun setClipProperties(
    p: Project,
    c: SetClipProperties,
): CommandResult {
    if (c.clipIds.isEmpty()) invalid("clipIds", "must not be empty")
    if (c.speed == null && c.volume == null && c.transform == null) {
        invalid("speed", "at least one of speed, volume, transform is required")
    }
    val k = c.speed?.let { normalizeSpeedCenti("speed", it) }
    c.volume?.let { checkVolume("volume", it) }
    val patch = c.transform?.let { normalizeTransformPatch(it) }
    val clips = p.requireClips(c.clipIds)
    for (clip in clips) {
        if (clip is CaptionClip &&
            k != null
        ) {
            invalid("speed", "${clip.id} is a caption; speed applies to media clips only")
        }
        if (clip is CaptionClip &&
            c.volume != null
        ) {
            invalid("volume", "${clip.id} is a caption; volume applies to media clips only")
        }
    }
    val listed = clips.associateBy { it.id }
    val changedTracks = LinkedHashMap<String, Track>()
    for (track in p.tracks) {
        if (track.clips.none { it.id in listed }) continue
        // Per clip: new version and its duration change (old end, delta), processed in start order.
        val deltas = mutableListOf<Pair<Long, Long>>()
        val updated =
            track.clips.map { old ->
                if (old.id !in listed) return@map old
                var n: Clip = old
                if (patch != null) {
                    val t = patch.applyTo(old.transform)
                    n =
                        when (n) {
                            is MediaClip -> n.copy(transform = t)
                            is CaptionClip -> n.copy(transform = t)
                        }
                }
                if (n is MediaClip) {
                    if (c.volume != null) n = n.copy(volume = c.volume)
                    if (k != null) {
                        val newDuration = (old.durationUs * n.speedCenti / 100) * 100 / k
                        if (newDuration < MIN) reject(CommandError.ClipTooShort(old.id, newDuration, MIN))
                        n = n.copy(durationUs = newDuration, speed = k / 100.0)
                        if (newDuration != old.durationUs) deltas += old.endUs to (newDuration - old.durationUs)
                    }
                }
                n
            }
        val result =
            if (c.ripple && deltas.isNotEmpty()) {
                // Each clip shifts by the summed deltas of resized clips whose old end is at or before its start.
                updated.mapIndexed { i, n ->
                    val oldStart = track.clips[i].startUs
                    n.shifted(deltas.filter { it.first <= oldStart }.sumOf { it.second })
                }
            } else {
                updated
            }
        for (clip in result) {
            if (clip.endUs > Limits.MAX_TIMELINE_US) invalid("speed", "exceeds maximum timeline length")
        }
        for (clip in result) {
            if (clip.id !in listed) continue
            val hit = conflicts(result, clip.startUs, clip.endUs, setOf(clip.id))
            if (hit.isNotEmpty()) reject(CommandError.Overlap(track.id, clip.startUs, clip.endUs, hit))
        }
        changedTracks[track.id] = track.withClips(result)
    }
    val what = if (clips.size == 1) clips[0].id else "${clips.size} clips"
    val parts =
        buildList {
            if (k != null) add("speed ${k / 100.0}x")
            if (c.volume != null) add("volume ${c.volume}")
            if (patch != null) add("transform")
        }
    return success(p.replaceTracks(changedTracks), "Set ${parts.joinToString(", ")} of $what")
}

internal fun addCaption(
    p: Project,
    c: AddCaption,
): CommandResult {
    if (c.captions.isEmpty()) invalid("captions", "must not be empty")
    val texts =
        c.captions.mapIndexed { i, spec ->
            checkTime("captions[$i].startUs", spec.startUs)
            checkTime("captions[$i].endUs", spec.endUs)
            normalizeText("captions[$i].text", spec.text)
        }
    val style = normalizeStyle(c.style)
    val transform = normalizeTransform("transform", c.transform)
    val existing = c.trackId?.let { p.requireTrack(it) } ?: p.tracks.firstOrNull { it.kind == TrackKind.CAPTION }
    if (existing != null && existing.kind != TrackKind.CAPTION) {
        reject(CommandError.TrackKindMismatch(existing.id, existing.kind, "captions go on caption tracks"))
    }
    for (spec in c.captions) {
        val d = spec.endUs - spec.startUs
        if (d < MIN) reject(CommandError.ClipTooShort(null, d, MIN))
    }
    val ids = IdAllocator(p.nextId)
    val track = existing ?: Track(ids.alloc("track"), TrackKind.CAPTION, "Captions 1")
    val placed = track.clips.toMutableList()
    val created = mutableListOf<String>()
    if (existing == null) created += track.id
    c.captions.forEachIndexed { i, spec ->
        val hit = conflicts(placed, spec.startUs, spec.endUs)
        if (hit.isNotEmpty()) reject(CommandError.Overlap(track.id, spec.startUs, spec.endUs, hit))
        val clip =
            CaptionClip(ids.alloc("clip"), spec.startUs, spec.endUs - spec.startUs, texts[i], style, transform)
        placed += clip
        created += clip.id
    }
    val newTrack = track.withClips(placed)
    val project =
        if (existing == null) {
            p.copy(tracks = p.tracks + newTrack)
        } else {
            p.replaceTracks(mapOf(track.id to newTrack))
        }
    val n = c.captions.size
    val what = if (n == 1) "caption \"${texts[0].take(40)}\"" else "$n captions"
    return success(project.withIds(ids), "Added $what to ${track.id}", created)
}

internal fun editCaption(
    p: Project,
    c: EditCaption,
): CommandResult {
    if (c.clipIds.isEmpty()) invalid("clipIds", "must not be empty")
    if (c.text == null && c.style == null) invalid("text", "text or style is required")
    if (c.text != null && c.clipIds.distinct().size > 1) invalid("text", "text can be set on one caption at a time")
    val text = c.text?.let { normalizeText("text", it) }
    val patch = c.style?.let { normalizeStylePatch(it) }
    val clips = p.requireClips(c.clipIds)
    for (clip in clips) {
        if (clip !is CaptionClip) invalid("clipIds", "${clip.id} is not a caption")
    }
    val ids = clips.map { it.id }.toSet()
    val changed = LinkedHashMap<String, Track>()
    for (track in p.tracks) {
        if (track.clips.none { it.id in ids }) continue
        changed[track.id] =
            track.copy(
                clips =
                    track.clips.map { clip ->
                        if (clip !is CaptionClip || clip.id !in ids) return@map clip
                        clip.copy(text = text ?: clip.text, style = patch?.applyTo(clip.style) ?: clip.style)
                    },
            )
    }
    val what = if (clips.size == 1) clips[0].id else "${clips.size} captions"
    val parts = listOfNotNull(text?.let { "text" }, patch?.let { "style" })
    return success(p.replaceTracks(changed), "Edited ${parts.joinToString(" and ")} of $what")
}

private fun checkRanges(ranges: List<TimeRange>) {
    if (ranges.isEmpty()) invalid("ranges", "must not be empty")
    ranges.forEachIndexed { i, r ->
        if (r.startUs < 0) invalid("ranges[$i].startUs", "must be >= 0")
        if (r.endUs <= r.startUs) invalid("ranges[$i].endUs", "must be greater than startUs")
    }
}

internal fun applyCuts(
    p: Project,
    c: ApplyCuts,
): CommandResult {
    checkRanges(c.ranges)
    val cuts = RangeCut.normalize(c.ranges, p.durationUs)
    if (cuts.isEmpty()) return CommandResult.Success(p, "No content in the given ranges", changed = false)
    val r = RangeCut.apply(p, cuts, null)
    val total = cuts.sumOf { it.durationUs }
    val n = cuts.size
    val dropped = if (r.droppedFragments > 0) "; dropped ${r.droppedFragments} fragment(s) under 10 ms" else ""
    return success(r.project, "Cut $n range${if (n == 1) "" else "s"} (${fmt(total)} total)$dropped", r.createdIds)
}

internal fun rippleDeleteRange(
    p: Project,
    c: RippleDeleteRange,
): CommandResult {
    checkTime("startUs", c.startUs)
    if (c.endUs <= c.startUs) invalid("endUs", "must be greater than startUs")
    if (c.trackIds != null && c.trackIds.isEmpty()) invalid("trackIds", "must not be empty")
    val trackIds =
        c.trackIds
            ?.distinct()
            ?.map { p.requireTrack(it).id }
            ?.toSet()
    val cuts = RangeCut.normalize(listOf(TimeRange(c.startUs, c.endUs)), p.durationUs)
    val label = "${fmt(c.startUs)}-${fmt(c.endUs)}"
    if (cuts.isEmpty()) return CommandResult.Success(p, "No content in $label", changed = false)
    val r = RangeCut.apply(p, cuts, trackIds)
    val where = if (trackIds == null) "all tracks" else trackIds.joinToString(", ")
    val dropped = if (r.droppedFragments > 0) "; dropped ${r.droppedFragments} fragment(s) under 10 ms" else ""
    return success(r.project, "Ripple deleted $label on $where$dropped", r.createdIds)
}
