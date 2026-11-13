package dev.videoeditor.timeline.validate

import dev.videoeditor.timeline.model.CaptionClip
import dev.videoeditor.timeline.model.CaptionStyle
import dev.videoeditor.timeline.model.Clip
import dev.videoeditor.timeline.model.Limits
import dev.videoeditor.timeline.model.MediaAsset
import dev.videoeditor.timeline.model.MediaClip
import dev.videoeditor.timeline.model.OutputSettings
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.Transform
import dev.videoeditor.timeline.model.endUs

enum class InvariantCode {
    ID_UNIQUE,
    ID_COUNTER,
    CLIP_ORDER,
    NO_OVERLAP,
    CLIP_TIME,
    CLIP_KIND,
    ASSET_REF,
    SOURCE_RANGE,
    SPEED,
    VOLUME,
    TRANSFORM,
    CAPTION,
    ASSET,
    OUTPUT,
}

data class Violation(
    val code: InvariantCode,
    val message: String,
    val ids: List<String> = emptyList(),
)

/** Checks every invariant of docs/design/timeline.md section 2 and returns all violations. */
object ProjectValidator {
    private val COLOR = Regex("^#[0-9A-F]{8}$")
    private val NUMBERED_ID = Regex("^[A-Za-z]+_(\\d+)$")

    fun isValid(project: Project): Boolean = validate(project).isEmpty()

    fun validate(project: Project): List<Violation> {
        val out = mutableListOf<Violation>()
        checkIds(project, out)
        checkOutput(project.output, out)
        project.assets.forEach { checkAsset(it, out) }
        for (track in project.tracks) {
            val clips = track.clips
            for (i in 1 until clips.size) {
                if (clips[i - 1].startUs > clips[i].startUs) {
                    out += Violation(InvariantCode.CLIP_ORDER, "clips of ${track.id} are not sorted", listOf(track.id))
                    break
                }
            }
            val sorted = clips.sortedBy { it.startUs }
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]
                val b = sorted[i]
                if (a.endUs > b.startUs) {
                    out +=
                        Violation(
                            InvariantCode.NO_OVERLAP,
                            "${a.id} overlaps ${b.id} on ${track.id}",
                            listOf(a.id, b.id),
                        )
                }
            }
            for (clip in clips) {
                checkClipTime(clip, out)
                checkTransform(clip.id, clip.transform, out)
                when (clip) {
                    is CaptionClip -> {
                        if (track.kind != TrackKind.CAPTION) {
                            out +=
                                Violation(
                                    InvariantCode.CLIP_KIND,
                                    "caption ${clip.id} on ${track.kind} track",
                                    listOf(clip.id),
                                )
                        }
                        checkCaption(clip, out)
                    }
                    is MediaClip -> checkMediaClip(project, track.kind, clip, out)
                }
            }
        }
        return out
    }

    private fun checkIds(
        project: Project,
        out: MutableList<Violation>,
    ) {
        val ids =
            buildList {
                project.assets.forEach { add(it.id) }
                project.tracks.forEach { t ->
                    add(t.id)
                    t.clips.forEach { add(it.id) }
                }
            }
        val seen = HashSet<String>()
        for (id in ids) {
            if (id.isBlank()) out += Violation(InvariantCode.ID_UNIQUE, "blank id")
            if (!seen.add(id)) out += Violation(InvariantCode.ID_UNIQUE, "duplicate id $id", listOf(id))
        }
        if (project.nextId < 1) out += Violation(InvariantCode.ID_COUNTER, "nextId must be >= 1")
        val maxSuffix = maxNumericSuffix(ids)
        if (maxSuffix != null && project.nextId <= maxSuffix) {
            out +=
                Violation(
                    InvariantCode.ID_COUNTER,
                    "nextId ${project.nextId} is not above existing id suffix $maxSuffix",
                )
        }
    }

    /** Largest N among ids of the form `prefix_N`, or null. */
    fun maxNumericSuffix(ids: Iterable<String>): Long? =
        ids
            .mapNotNull {
                NUMBERED_ID
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            }.maxOrNull()

    private fun checkOutput(
        o: OutputSettings,
        out: MutableList<Violation>,
    ) {
        fun dimOk(d: Int) = d % 2 == 0 && d in Limits.MIN_OUTPUT_DIM..Limits.MAX_OUTPUT_DIM
        if (!dimOk(o.width) || !dimOk(o.height)) {
            out += Violation(InvariantCode.OUTPUT, "output size ${o.width}x${o.height} must be even and in 16..8192")
        }
        if (o.frameRate !in Limits.MIN_FPS..Limits.MAX_FPS) {
            out += Violation(InvariantCode.OUTPUT, "output frame rate ${o.frameRate} must be in 1..120")
        }
    }

    /** Null if the asset satisfies invariant ASSET, else a reason. Shared with ImportMedia. */
    fun assetProblem(a: MediaAsset): String? =
        when {
            a.uri.isBlank() -> "uri must not be blank"
            a.durationUs !in 1..Limits.MAX_TIMELINE_US -> "durationUs must be in 1..${Limits.MAX_TIMELINE_US}"
            !a.hasVideo && !a.hasAudio -> "asset must have video or audio"
            a.hasVideo && ((a.width ?: 0) <= 0 || (a.height ?: 0) <= 0) -> "video assets need positive width and height"
            a.frameRate != null && !(a.frameRate.isFinite() && a.frameRate > 0) ->
                "frameRate must be finite and positive"
            else -> null
        }

    private fun checkAsset(
        a: MediaAsset,
        out: MutableList<Violation>,
    ) {
        assetProblem(a)?.let { out += Violation(InvariantCode.ASSET, "${a.id}: $it", listOf(a.id)) }
    }

    private fun checkClipTime(
        c: Clip,
        out: MutableList<Violation>,
    ) {
        if (!clipTimeOk(c)) {
            out +=
                Violation(
                    InvariantCode.CLIP_TIME,
                    "${c.id}: invalid time [${c.startUs}, +${c.durationUs})",
                    listOf(c.id),
                )
        }
    }

    private fun clipTimeOk(c: Clip): Boolean =
        c.startUs >= 0 &&
            c.durationUs >= Limits.MIN_CLIP_DURATION_US &&
            c.startUs <= Limits.MAX_TIMELINE_US &&
            c.durationUs <= Limits.MAX_TIMELINE_US &&
            c.endUs <= Limits.MAX_TIMELINE_US

    /** Null if the transform is valid, else `field: reason`. */
    fun transformProblem(t: Transform): String? =
        when {
            !t.translateX.isFinite() || Math.abs(t.translateX) > Limits.MAX_TRANSLATE -> "translateX must be in -2..2"
            !t.translateY.isFinite() || Math.abs(t.translateY) > Limits.MAX_TRANSLATE -> "translateY must be in -2..2"
            !t.scale.isFinite() || t.scale < Limits.MIN_SCALE || t.scale > Limits.MAX_SCALE ->
                "scale must be in 0.01..10"
            !t.rotationDeg.isFinite() || t.rotationDeg < 0.0 || t.rotationDeg >= 360.0 ->
                "rotationDeg must be in [0, 360)"
            !t.opacity.isFinite() || t.opacity < 0.0 || t.opacity > 1.0 -> "opacity must be in 0..1"
            else -> null
        }

    private fun checkTransform(
        id: String,
        t: Transform,
        out: MutableList<Violation>,
    ) {
        transformProblem(t)?.let { out += Violation(InvariantCode.TRANSFORM, "$id: $it", listOf(id)) }
    }

    /** Null if the style is valid, else a reason. */
    fun styleProblem(s: CaptionStyle): String? =
        when {
            !s.fontSizeFrac.isFinite() || s.fontSizeFrac <= 0.0 || s.fontSizeFrac > Limits.MAX_FONT_SIZE_FRAC ->
                "fontSizeFrac must be in (0, 0.5]"
            !COLOR.matches(s.color) -> "color must be #AARRGGBB"
            s.backgroundColor != null && !COLOR.matches(s.backgroundColor) -> "backgroundColor must be #AARRGGBB"
            s.fontFamily != null && s.fontFamily.isBlank() -> "fontFamily must not be blank"
            else -> null
        }

    /** Null if caption text is valid. */
    fun textProblem(text: String): String? =
        when {
            text.isBlank() -> "text must not be blank"
            text.length > Limits.MAX_CAPTION_CHARS -> "text must be at most ${Limits.MAX_CAPTION_CHARS} characters"
            else -> null
        }

    fun isValidColor(color: String): Boolean = COLOR.matches(color)

    private fun checkCaption(
        c: CaptionClip,
        out: MutableList<Violation>,
    ) {
        textProblem(c.text)?.let { out += Violation(InvariantCode.CAPTION, "${c.id}: $it", listOf(c.id)) }
        styleProblem(c.style)?.let { out += Violation(InvariantCode.CAPTION, "${c.id}: $it", listOf(c.id)) }
    }

    private fun checkMediaClip(
        project: Project,
        kind: TrackKind,
        c: MediaClip,
        out: MutableList<Violation>,
    ) {
        val speedOk =
            c.speed.isFinite() &&
                c.speedCenti in Limits.MIN_SPEED_CENTI..Limits.MAX_SPEED_CENTI &&
                c.speed == c.speedCenti / 100.0
        if (!speedOk) out += Violation(InvariantCode.SPEED, "${c.id}: invalid speed ${c.speed}", listOf(c.id))
        if (!c.volume.isFinite() || c.volume < 0.0 || c.volume > Limits.MAX_VOLUME) {
            out += Violation(InvariantCode.VOLUME, "${c.id}: volume must be in 0..2", listOf(c.id))
        }
        val asset = project.asset(c.assetId)
        if (asset == null) {
            out += Violation(InvariantCode.ASSET_REF, "${c.id}: unknown asset ${c.assetId}", listOf(c.id, c.assetId))
        }
        when (kind) {
            TrackKind.CAPTION ->
                out +=
                    Violation(InvariantCode.CLIP_KIND, "media ${c.id} on caption track", listOf(c.id))
            TrackKind.VIDEO, TrackKind.OVERLAY ->
                if (asset != null && !asset.hasVideo) {
                    out += Violation(InvariantCode.CLIP_KIND, "${c.id}: asset has no video", listOf(c.id))
                }
            TrackKind.AUDIO ->
                if (asset != null && !asset.hasAudio) {
                    out += Violation(InvariantCode.CLIP_KIND, "${c.id}: asset has no audio", listOf(c.id))
                }
        }
        if (asset != null &&
            speedOk &&
            clipTimeOk(c) &&
            (c.sourceInUs < 0 || c.sourceInUs > asset.durationUs || c.sourceOutUs > asset.durationUs)
        ) {
            out +=
                Violation(
                    InvariantCode.SOURCE_RANGE,
                    "${c.id}: source [${c.sourceInUs}, ${c.sourceOutUs}) outside asset of ${asset.durationUs}",
                    listOf(c.id),
                )
        }
    }
}
