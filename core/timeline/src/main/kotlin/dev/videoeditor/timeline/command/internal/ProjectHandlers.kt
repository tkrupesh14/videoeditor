package dev.videoeditor.timeline.command.internal

import dev.videoeditor.timeline.command.AddTrack
import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.command.RemoveTrack
import dev.videoeditor.timeline.command.SetProjectSettings
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind

internal fun kindLabel(kind: TrackKind): String =
    when (kind) {
        TrackKind.VIDEO -> "Video"
        TrackKind.AUDIO -> "Audio"
        TrackKind.OVERLAY -> "Overlay"
        TrackKind.CAPTION -> "Captions"
    }

internal fun importMedia(
    p: Project,
    c: ImportMedia,
): CommandResult {
    if (c.uri.isBlank()) invalid("uri", "must not be blank")
    if (c.durationUs <= 0 || c.durationUs > Limits.MAX_TIMELINE_US) {
        invalid("durationUs", "must be in 1..${Limits.MAX_TIMELINE_US}")
    }
    if (!c.hasVideo && !c.hasAudio) invalid("hasVideo", "asset must have video or audio")
    if (c.width != null && c.width <= 0) invalid("width", "must be positive")
    if (c.height != null && c.height <= 0) invalid("height", "must be positive")
    if (c.hasVideo && (c.width == null || c.height == null)) invalid("width", "video assets need width and height")
    if (c.frameRate != null &&
        !(c.frameRate.isFinite() && c.frameRate > 0)
    ) {
        invalid("frameRate", "must be finite and positive")
    }

    p.assets.firstOrNull { it.uri == c.uri }?.let {
        return CommandResult.Success(p, "Media already imported as ${it.id}", changed = false)
    }
    val ids = IdAllocator(p.nextId)
    val asset =
        MediaAsset(
            ids.alloc("asset"),
            c.uri,
            c.durationUs,
            c.hasVideo,
            c.hasAudio,
            c.width,
            c.height,
            c.frameRate,
            c.displayName,
        )
    val name = c.displayName?.let { " ($it)" } ?: ""
    return success(
        p.copy(assets = p.assets + asset).withIds(ids),
        "Imported ${asset.id}$name, ${fmt(asset.durationUs)} long",
        listOf(asset.id),
    )
}

internal fun addTrack(
    p: Project,
    c: AddTrack,
): CommandResult {
    if (c.name != null && c.name.isBlank()) invalid("name", "must not be blank")
    if (c.index != null && c.index !in 0..p.tracks.size) invalid("index", "must be in 0..${p.tracks.size}")
    val ids = IdAllocator(p.nextId)
    val name = c.name ?: "${kindLabel(c.kind)} ${p.tracks.count { it.kind == c.kind } + 1}"
    val track = Track(ids.alloc("track"), c.kind, name)
    val tracks = p.tracks.toMutableList()
    tracks.add(c.index ?: tracks.size, track)
    return success(
        p.copy(tracks = tracks).withIds(ids),
        "Added ${c.kind.name.lowercase()} track ${track.id} \"$name\"",
        listOf(track.id),
    )
}

internal fun removeTrack(
    p: Project,
    c: RemoveTrack,
): CommandResult {
    val track = p.requireTrack(c.trackId)
    if (track.clips.isNotEmpty() && !c.deleteClips) reject(CommandError.TrackNotEmpty(track.id, track.clips.size))
    val extra = if (track.clips.isNotEmpty()) " and its ${track.clips.size} clips" else ""
    return success(p.copy(tracks = p.tracks.filter { it.id != track.id }), "Removed track ${track.id}$extra")
}

internal fun setProjectSettings(
    p: Project,
    c: SetProjectSettings,
): CommandResult {
    if (c.name == null && c.width == null && c.height == null && c.frameRate == null) {
        invalid("name", "at least one of name, width, height, frameRate is required")
    }
    if (c.name != null && c.name.isBlank()) invalid("name", "must not be blank")

    fun dim(
        field: String,
        v: Int?,
    ) {
        if (v != null && (v % 2 != 0 || v !in Limits.MIN_OUTPUT_DIM..Limits.MAX_OUTPUT_DIM)) {
            invalid(field, "must be even and in ${Limits.MIN_OUTPUT_DIM}..${Limits.MAX_OUTPUT_DIM}")
        }
    }
    dim("width", c.width)
    dim("height", c.height)
    if (c.frameRate != null && c.frameRate !in Limits.MIN_FPS..Limits.MAX_FPS) {
        invalid("frameRate", "must be in ${Limits.MIN_FPS}..${Limits.MAX_FPS}")
    }
    val o = p.output
    val out = o.copy(width = c.width ?: o.width, height = c.height ?: o.height, frameRate = c.frameRate ?: o.frameRate)
    val parts =
        buildList {
            if (c.name != null) add("name \"${c.name}\"")
            if (c.width != null || c.height != null) add("size ${out.width}x${out.height}")
            if (c.frameRate != null) add("${out.frameRate} fps")
        }
    return success(p.copy(name = c.name ?: p.name, output = out), "Set project ${parts.joinToString(", ")}")
}
