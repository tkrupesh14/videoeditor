package dev.videoeditor.timeline.command.internal

import dev.videoeditor.timeline.command.AddClip
import dev.videoeditor.timeline.command.ClipEdge
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.endUs

private const val MIN = Limits.MIN_CLIP_DURATION_US

internal fun addClip(
    p: Project,
    c: AddClip,
): CommandResult {
    // (1) argument shape
    c.startUs?.let { checkTime("startUs", it) }
    val k = normalizeSpeedCenti("speed", c.speed)
    checkVolume("volume", c.volume)
    val transform = normalizeTransform("transform", c.transform)
    // (2) lookups in field order
    val track = p.requireTrack(c.trackId)
    val asset = p.requireAsset(c.assetId)
    // (3) kind
    mediaKindProblem(asset, track, "asset ${asset.id}")?.let { reject(it) }
    // (4) source and time bounds
    val sourceOut = c.sourceOutUs ?: asset.durationUs
    if (c.sourceInUs < 0 || c.sourceInUs >= sourceOut || sourceOut > asset.durationUs) {
        reject(CommandError.SourceOutOfRange(null, asset.id, c.sourceInUs, sourceOut, asset.durationUs))
    }
    val duration = (sourceOut - c.sourceInUs) * 100 / k
    if (duration < MIN) reject(CommandError.ClipTooShort(null, duration, MIN))
    val start = c.startUs ?: track.endUs
    if (start + duration > Limits.MAX_TIMELINE_US) invalid("startUs", "exceeds maximum timeline length")
    // (5) overlap
    val others: List<Clip>
    if (c.ripple) {
        val straddling = track.clips.filter { it.startUs < start && start < it.endUs }.map { it.id }
        if (straddling.isNotEmpty()) reject(CommandError.Overlap(track.id, start, start + duration, straddling))
        others = track.clips.map { if (it.startUs >= start) it.shifted(duration) else it }
        checkTrackEnds(others)
    } else {
        val hit = conflicts(track.clips, start, start + duration)
        if (hit.isNotEmpty()) reject(CommandError.Overlap(track.id, start, start + duration, hit))
        others = track.clips
    }
    val ids = IdAllocator(p.nextId)
    val clip =
        MediaClip(ids.alloc("clip"), asset.id, start, duration, c.sourceInUs, k / 100.0, c.volume, transform)
    val newTrack = track.withClips(others + clip)
    val how = if (c.ripple) "Inserted" else "Added"
    return success(
        p.replaceTracks(mapOf(track.id to newTrack)).withIds(ids),
        "$how ${clip.id} (${asset.id}) on ${track.id} at ${fmt(start)}",
        listOf(clip.id),
    )
}

internal fun splitClip(
    p: Project,
    c: SplitClip,
): CommandResult {
    val clips = p.requireClips(c.clipIds)
    for (clip in clips) {
        val minUs = clip.startUs + MIN
        val maxUs = clip.endUs - MIN
        if (c.atUs < minUs || c.atUs > maxUs) reject(CommandError.SplitOutOfRange(clip.id, c.atUs, minUs, maxUs))
    }
    val ids = IdAllocator(p.nextId)
    val created = mutableListOf<String>()
    val byTrack = LinkedHashMap<String, MutableList<Clip>>()
    for (clip in clips) {
        val track = p.trackOf(clip.id)!!
        val list = byTrack.getOrPut(track.id) { track.clips.toMutableList() }
        val idx = list.indexOfFirst { it.id == clip.id }
        val oldEnd = clip.endUs
        val rightId = ids.alloc("clip")
        created += rightId
        val left = clip.withTiming(clip.startUs, c.atUs - clip.startUs)
        val right =
            when (clip) {
                is MediaClip ->
                    clip.copy(
                        id = rightId,
                        startUs = c.atUs,
                        durationUs = oldEnd - c.atUs,
                        sourceInUs = clip.sourceInUs + (c.atUs - clip.startUs) * clip.speedCenti / 100,
                    )
                is CaptionClip -> clip.copy(id = rightId, startUs = c.atUs, durationUs = oldEnd - c.atUs)
            }
        list[idx] = left
        list.add(idx + 1, right)
    }
    val tracks = byTrack.mapValues { (id, list) -> p.track(id)!!.copy(clips = list) }
    val what = if (clips.size == 1) clips[0].id else "${clips.size} clips"
    return success(p.replaceTracks(tracks).withIds(ids), "Split $what at ${fmt(c.atUs)}", created)
}

internal fun trimClip(
    p: Project,
    c: TrimClip,
): CommandResult {
    checkTime("toUs", c.toUs)
    val clip = p.requireClip(c.clipId)
    val track = p.trackOf(clip.id)!!
    val s = clip.startUs
    val e = clip.endUs
    val current = if (c.edge == ClipEdge.START) s else e
    if (c.toUs == current) return CommandResult.Success(p, "Trim of ${clip.id} unchanged", changed = false)

    val newStart: Long
    val newDuration: Long
    var newSourceIn: Long? = null
    val followerShift: Long
    if (c.edge == ClipEdge.END) {
        newStart = s
        newDuration = c.toUs - s
        followerShift = c.toUs - e
    } else {
        newStart = if (c.ripple) s else c.toUs
        newDuration = e - c.toUs
        followerShift = s - c.toUs
    }
    if (newDuration < MIN) reject(CommandError.ClipTooShort(clip.id, newDuration, MIN))
    if (clip is MediaClip) {
        val k = clip.speedCenti
        // Symmetric (toward-zero) rounding of the source delta so a START trim and its inverse cancel exactly
        // and never undershoot source 0; the range check below still guarantees the asset bounds.
        val sIn =
            if (c.edge == ClipEdge.START) {
                val d = c.toUs - s
                clip.sourceInUs + if (d >= 0) d * k / 100 else -((-d) * k / 100)
            } else {
                clip.sourceInUs
            }
        newSourceIn = sIn
        val asset = p.requireAsset(clip.assetId)
        val sOut = sIn + newDuration * k / 100
        if (sIn < 0 || sOut > asset.durationUs) {
            reject(CommandError.SourceOutOfRange(clip.id, asset.id, sIn, sOut, asset.durationUs))
        }
    }
    var trimmed = clip.withTiming(newStart, newDuration)
    if (trimmed is MediaClip && newSourceIn != null) trimmed = trimmed.copy(sourceInUs = newSourceIn)

    val clips: List<Clip>
    if (c.ripple) {
        clips =
            track.clips.map {
                if (it.id ==
                    clip.id
                ) {
                    trimmed
                } else if (it.startUs >= e) {
                    it.shifted(followerShift)
                } else {
                    it
                }
            }
        checkTrackEnds(clips)
    } else {
        if (trimmed.endUs > Limits.MAX_TIMELINE_US) invalid("toUs", "exceeds maximum timeline length")
        val hit = conflicts(track.clips, trimmed.startUs, trimmed.endUs, setOf(clip.id))
        if (hit.isNotEmpty()) reject(CommandError.Overlap(track.id, trimmed.startUs, trimmed.endUs, hit))
        clips = track.clips.map { if (it.id == clip.id) trimmed else it }
    }
    val edge = if (c.edge == ClipEdge.START) "start" else "end"
    val rip = if (c.ripple) " (ripple)" else ""
    return success(
        p.replaceTracks(mapOf(track.id to track.withClips(clips))),
        "Trimmed $edge of ${clip.id} to ${fmt(c.toUs)}$rip",
    )
}

internal fun moveClip(
    p: Project,
    c: MoveClip,
): CommandResult {
    if (c.clipIds.isEmpty()) invalid("clipIds", "must not be empty")
    if (c.toStartUs == null && c.toTrackId == null) invalid("toStartUs", "toStartUs or toTrackId is required")
    c.toStartUs?.let { checkTime("toStartUs", it) }
    val clips = p.requireClips(c.clipIds)
    val target = c.toTrackId?.let { p.requireTrack(it) }
    val sourceTrack = clips.associate { it.id to p.trackOf(it.id)!! }
    if (target != null &&
        sourceTrack.values
            .map { it.id }
            .distinct()
            .size > 1
    ) {
        invalid("clipIds", "all clips must be on one track when toTrackId is set")
    }
    for (clip in clips) kindProblem(p, clip, target ?: sourceTrack.getValue(clip.id))?.let { reject(it) }
    val minStart = clips.minOf { it.startUs }
    val delta = (c.toStartUs ?: minStart) - minStart
    if (delta == 0L && (target == null || target.id == sourceTrack.getValue(clips[0].id).id)) {
        return CommandResult.Success(p, "Move left clips in place", changed = false)
    }
    val moved = clips.map { it.shifted(delta) }
    for (m in moved) {
        if (m.startUs < 0) invalid("toStartUs", "would move ${m.id} before 0")
        if (m.endUs > Limits.MAX_TIMELINE_US) invalid("toStartUs", "exceeds maximum timeline length")
    }
    val movingIds = clips.map { it.id }.toSet()
    // Remove the group, then place each moved clip against the clips that did not move.
    val remaining = p.tracks.associate { t -> t.id to t.clips.filter { it.id !in movingIds }.toMutableList() }
    for (m in moved) {
        val destId = target?.id ?: sourceTrack.getValue(m.id).id
        val dest = remaining.getValue(destId)
        val hit = conflicts(dest, m.startUs, m.endUs)
        if (hit.isNotEmpty()) reject(CommandError.Overlap(destId, m.startUs, m.endUs, hit))
    }
    for (m in moved) remaining.getValue(target?.id ?: sourceTrack.getValue(m.id).id) += m
    val touched =
        (
            moved.map {
                target?.id ?: sourceTrack
                    .getValue(
                        it.id,
                    ).id
            } + sourceTrack.values.map { it.id }
        ).toSet()
    val tracks = touched.associateWith { id -> p.track(id)!!.withClips(remaining.getValue(id)) }
    val what = if (clips.size == 1) clips[0].id else "${clips.size} clips"
    val where =
        buildString {
            if (delta != 0L) append(" to ${fmt(minStart + delta)}")
            if (target != null) append(" on ${target.id}")
        }
    return success(p.replaceTracks(tracks), "Moved $what$where")
}

internal fun deleteClips(
    p: Project,
    c: DeleteClips,
): CommandResult {
    val clips = p.requireClips(c.clipIds)
    val ids = clips.map { it.id }.toSet()
    val changed = LinkedHashMap<String, Track>()
    for (track in p.tracks) {
        if (track.clips.none { it.id in ids }) continue
        val deleted = track.clips.filter { it.id in ids }
        val survivors =
            track.clips.filter { it.id !in ids }.map { s ->
                if (c.ripple) s.shifted(-deleted.filter { it.endUs <= s.startUs }.sumOf { it.durationUs }) else s
            }
        changed[track.id] = track.copy(clips = survivors)
    }
    val what = if (clips.size == 1) clips[0].id else "${clips.size} clips"
    val rip = if (c.ripple) " (ripple)" else ""
    return success(p.replaceTracks(changed), "Deleted $what$rip")
}
