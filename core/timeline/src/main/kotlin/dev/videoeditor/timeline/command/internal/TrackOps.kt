package dev.videoeditor.timeline.command.internal

import dev.videoeditor.timeline.command.CommandError
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.command.EntityKind
import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.CaptionStyle
import dev.videoeditor.timeline.model.CaptionStylePatch
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TimeMath
import dev.videoeditor.timeline.model.Track
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.Transform
import dev.videoeditor.timeline.model.TransformPatch
import dev.videoeditor.timeline.model.endUs
import dev.videoeditor.timeline.validate.ProjectValidator

/** Thrown inside handlers to short-circuit with a domain error; caught by the executor. Never escapes. */
internal class Reject(
    val error: CommandError,
) : RuntimeException(null, null, false, false)

internal fun reject(error: CommandError): Nothing = throw Reject(error)

internal fun invalid(
    field: String,
    reason: String,
): Nothing = reject(CommandError.InvalidArgument(field, reason))

internal fun fmt(us: Long): String = TimeMath.formatTimecode(us)

internal fun success(
    project: Project,
    summary: String,
    createdIds: List<String> = emptyList(),
): CommandResult.Success = CommandResult.Success(project, summary, true, createdIds)

/** Sequential ID allocation from the project's counter (design D4). */
internal class IdAllocator(
    var next: Long,
) {
    fun alloc(prefix: String): String = "${prefix}_${next++}"
}

internal fun Project.withIds(ids: IdAllocator): Project = copy(nextId = ids.next)

internal fun Project.requireTrack(id: String): Track = track(id) ?: reject(CommandError.NotFound(EntityKind.TRACK, id))

internal fun Project.requireAsset(id: String): MediaAsset =
    asset(id) ?: reject(CommandError.NotFound(EntityKind.ASSET, id))

internal fun Project.requireClip(id: String): Clip = clip(id) ?: reject(CommandError.NotFound(EntityKind.CLIP, id))

/** Non-empty, de-duplicated (first occurrence kept) and all found, in list order. */
internal fun Project.requireClips(
    ids: List<String>,
    field: String = "clipIds",
): List<Clip> {
    if (ids.isEmpty()) invalid(field, "must not be empty")
    return ids.distinct().map { requireClip(it) }
}

internal fun Project.replaceTracks(changed: Map<String, Track>): Project =
    if (changed.isEmpty()) this else copy(tracks = tracks.map { changed[it.id] ?: it })

internal fun Track.withClips(clips: List<Clip>): Track = copy(clips = clips.sortedBy { it.startUs })

/** Clips in [clips] (excluding [ignore]) that intersect [startUs, endUs). */
internal fun conflicts(
    clips: List<Clip>,
    startUs: Long,
    endUs: Long,
    ignore: Set<String> = emptySet(),
): List<String> = clips.filter { it.id !in ignore && it.startUs < endUs && startUs < it.endUs }.map { it.id }

internal fun checkTime(
    field: String,
    value: Long,
) {
    if (value < 0) invalid(field, "must be >= 0")
    if (value > Limits.MAX_TIMELINE_US) invalid(field, "exceeds maximum timeline length")
}

internal fun checkEnd(endUs: Long) {
    if (endUs > Limits.MAX_TIMELINE_US) invalid("endUs", "exceeds maximum timeline length")
}

internal fun Clip.shifted(delta: Long): Clip =
    if (delta == 0L) {
        this
    } else {
        when (this) {
            is MediaClip -> copy(startUs = startUs + delta)
            is CaptionClip -> copy(startUs = startUs + delta)
        }
    }

internal fun Clip.withTiming(
    startUs: Long,
    durationUs: Long,
): Clip =
    when (this) {
        is MediaClip -> copy(startUs = startUs, durationUs = durationUs)
        is CaptionClip -> copy(startUs = startUs, durationUs = durationUs)
    }

internal fun Clip.withId(id: String): Clip =
    when (this) {
        is MediaClip -> copy(id = id)
        is CaptionClip -> copy(id = id)
    }

/** Normalized speed in centi, or rejection. */
internal fun normalizeSpeedCenti(
    field: String,
    speed: Double,
): Int {
    if (!speed.isFinite()) invalid(field, "must be a finite number")
    val centi = TimeMath.speedCenti(TimeMath.normalizeSpeed(speed).coerceIn(-1e6, 1e6))
    if (centi !in Limits.MIN_SPEED_CENTI..Limits.MAX_SPEED_CENTI) invalid(field, "must be in 0.1..10")
    return centi
}

internal fun checkVolume(
    field: String,
    volume: Double,
) {
    if (!volume.isFinite() || volume < 0.0 || volume > Limits.MAX_VOLUME) invalid(field, "must be in 0..2")
}

internal fun normalizeTransform(
    field: String,
    t: Transform,
): Transform {
    if (!t.rotationDeg.isFinite()) invalid("$field.rotationDeg", "must be finite")
    val n = t.copy(rotationDeg = TimeMath.normalizeRotation(t.rotationDeg))
    ProjectValidator.transformProblem(n)?.let { invalid("$field.${it.substringBefore(' ')}", it) }
    return n
}

internal fun TransformPatch.applyTo(t: Transform): Transform =
    Transform(
        translateX = translateX ?: t.translateX,
        translateY = translateY ?: t.translateY,
        scale = scale ?: t.scale,
        rotationDeg = rotationDeg ?: t.rotationDeg,
        opacity = opacity ?: t.opacity,
    )

/** Validates a patch on its own (each non-null field in range); returns it with rotation normalized. */
internal fun normalizeTransformPatch(p: TransformPatch): TransformPatch {
    val rot =
        p.rotationDeg?.let {
            if (!it.isFinite()) invalid("transform.rotationDeg", "must be finite") else TimeMath.normalizeRotation(it)
        }
    val n = p.copy(rotationDeg = rot)
    normalizeTransform("transform", n.applyTo(Transform()))
    return n
}

internal fun normalizeColor(
    field: String,
    color: String,
): String {
    val up = color.uppercase()
    if (!ProjectValidator.isValidColor(up)) invalid(field, "must be #AARRGGBB")
    return up
}

internal fun normalizeStyle(s: CaptionStyle): CaptionStyle {
    val n =
        s.copy(
            color = normalizeColor("style.color", s.color),
            backgroundColor = s.backgroundColor?.let { normalizeColor("style.backgroundColor", it) },
        )
    ProjectValidator.styleProblem(n)?.let { invalid("style.${it.substringBefore(' ')}", it) }
    return n
}

internal fun CaptionStylePatch.applyTo(s: CaptionStyle): CaptionStyle =
    CaptionStyle(
        fontSizeFrac = fontSizeFrac ?: s.fontSizeFrac,
        color = color ?: s.color,
        backgroundColor =
            when (backgroundColor) {
                null -> s.backgroundColor
                "" -> null
                else -> backgroundColor
            },
        bold = bold ?: s.bold,
        italic = italic ?: s.italic,
        align = align ?: s.align,
        fontFamily =
            when (fontFamily) {
                null -> s.fontFamily
                "" -> null
                else -> fontFamily
            },
    )

internal fun normalizeStylePatch(p: CaptionStylePatch): CaptionStylePatch {
    val n =
        p.copy(
            color = p.color?.let { normalizeColor("style.color", it) },
            backgroundColor =
                p.backgroundColor?.let {
                    if (it.isEmpty()) {
                        it
                    } else {
                        normalizeColor(
                            "style.backgroundColor",
                            it,
                        )
                    }
                },
        )
    ProjectValidator.styleProblem(n.applyTo(CaptionStyle()))?.let { invalid("style.${it.substringBefore(' ')}", it) }
    return n
}

internal fun normalizeText(
    field: String,
    text: String,
): String {
    val t = text.trim()
    ProjectValidator.textProblem(t)?.let { invalid(field, it) }
    return t
}

/** Null if a clip of this kind can live on [track]; else the mismatch error. */
internal fun kindProblem(
    project: Project,
    clip: Clip,
    track: Track,
): CommandError.TrackKindMismatch? {
    fun err(reason: String) = CommandError.TrackKindMismatch(track.id, track.kind, reason)
    return when (clip) {
        is CaptionClip -> if (track.kind != TrackKind.CAPTION) err("captions go on caption tracks") else null
        is MediaClip -> mediaKindProblem(project.asset(clip.assetId), track, clip.id)
    }
}

internal fun mediaKindProblem(
    asset: MediaAsset?,
    track: Track,
    clipLabel: String,
): CommandError.TrackKindMismatch? {
    fun err(reason: String) = CommandError.TrackKindMismatch(track.id, track.kind, reason)
    return when (track.kind) {
        TrackKind.CAPTION -> err("$clipLabel: media clips cannot go on a caption track; use add_caption")
        TrackKind.VIDEO, TrackKind.OVERLAY ->
            if (asset != null &&
                !asset.hasVideo
            ) {
                err("$clipLabel: asset ${asset.id} has no video")
            } else {
                null
            }
        TrackKind.AUDIO ->
            if (asset != null &&
                !asset.hasAudio
            ) {
                err("$clipLabel: asset ${asset.id} has no audio")
            } else {
                null
            }
    }
}

/** Fails if a clip end would pass the maximum timeline length. */
internal fun checkTrackEnds(clips: Iterable<Clip>) {
    for (c in clips) if (c.endUs > Limits.MAX_TIMELINE_US) invalid("shift", "exceeds maximum timeline length")
}
