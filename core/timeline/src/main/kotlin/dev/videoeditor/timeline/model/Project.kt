package dev.videoeditor.timeline.model

import dev.videoeditor.timeline.schema.Desc
import dev.videoeditor.timeline.schema.Min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Desc("A half-open time range [startUs, endUs) in microseconds.")
data class TimeRange(
    @Desc("Range start (inclusive), in microseconds (1 second = 1000000).")
    @Min(0)
    val startUs: Long,
    @Desc("Range end (exclusive), in microseconds (1 second = 1000000). Must be greater than startUs.")
    @Min(0)
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

@Serializable
data class OutputSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
)

@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val durationUs: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val displayName: String? = null,
)

@Serializable
enum class TrackKind { VIDEO, AUDIO, OVERLAY, CAPTION }

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val name: String,
    val clips: List<Clip> = emptyList(),
) {
    /** Last clip end, 0 if empty. */
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L
}

@Serializable
sealed interface Clip {
    val id: String
    val startUs: Long
    val durationUs: Long
    val transform: Transform
}

val Clip.endUs: Long get() = startUs + durationUs
val Clip.range: TimeRange get() = TimeRange(startUs, endUs)

@Serializable
@SerialName("media")
data class MediaClip(
    override val id: String,
    val assetId: String,
    override val startUs: Long,
    override val durationUs: Long,
    val sourceInUs: Long,
    val speed: Double = 1.0,
    val volume: Double = 1.0,
    override val transform: Transform = Transform(),
) : Clip {
    val speedCenti: Int get() = TimeMath.speedCenti(speed)
    val sourceDurationUs: Long get() = durationUs * speedCenti / 100
    val sourceOutUs: Long get() = sourceInUs + sourceDurationUs
}

@Serializable
@SerialName("caption")
data class CaptionClip(
    override val id: String,
    override val startUs: Long,
    override val durationUs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle(),
    override val transform: Transform = Transform(translateY = 0.35),
) : Clip

@Serializable
@Desc("Placement of a clip in the output frame.")
data class Transform(
    @Desc(
        "Horizontal offset of the clip centre from the frame centre, as a fraction of output width, -2..2. +x is right.",
    )
    val translateX: Double = 0.0,
    @Desc(
        "Vertical offset of the clip centre from the frame centre, as a fraction of output height, -2..2. +y is down.",
    )
    val translateY: Double = 0.0,
    @Desc("Scale, 0.01..10. 1.0 = aspect-fit inside the frame for media, natural size for captions.")
    val scale: Double = 1.0,
    @Desc("Clockwise rotation in degrees; normalized to [0, 360).")
    val rotationDeg: Double = 0.0,
    @Desc("Opacity, 0..1.")
    val opacity: Double = 1.0,
)

@Serializable
@Desc("Partial transform: only the given fields are changed.")
data class TransformPatch(
    @Desc("New horizontal offset, fraction of output width, -2..2.")
    val translateX: Double? = null,
    @Desc("New vertical offset, fraction of output height, -2..2.")
    val translateY: Double? = null,
    @Desc("New scale, 0.01..10.")
    val scale: Double? = null,
    @Desc("New clockwise rotation in degrees.")
    val rotationDeg: Double? = null,
    @Desc("New opacity, 0..1.")
    val opacity: Double? = null,
)

@Serializable
enum class TextAlign { START, CENTER, END }

@Serializable
@Desc("Caption text style.")
data class CaptionStyle(
    @Desc("Font size as a fraction of output height, greater than 0 and at most 0.5.")
    val fontSizeFrac: Double = 0.05,
    @Desc("Text colour as #AARRGGBB.")
    val color: String = "#FFFFFFFF",
    @Desc("Background box colour as #AARRGGBB; omit for none.")
    val backgroundColor: String? = null,
    @Desc("Bold text.")
    val bold: Boolean = false,
    @Desc("Italic text.")
    val italic: Boolean = false,
    @Desc("Horizontal text alignment.")
    val align: TextAlign = TextAlign.CENTER,
    @Desc("Font family name; omit for the app default.")
    val fontFamily: String? = null,
)

@Serializable
@Desc("Partial caption style: only the given fields are changed.")
data class CaptionStylePatch(
    @Desc("New font size as a fraction of output height, greater than 0 and at most 0.5.")
    val fontSizeFrac: Double? = null,
    @Desc("New text colour as #AARRGGBB.")
    val color: String? = null,
    @Desc("New background colour as #AARRGGBB; empty string clears the background.")
    val backgroundColor: String? = null,
    @Desc("Bold text.")
    val bold: Boolean? = null,
    @Desc("Italic text.")
    val italic: Boolean? = null,
    @Desc("Horizontal text alignment.")
    val align: TextAlign? = null,
    @Desc("Font family name; empty string resets to the app default.")
    val fontFamily: String? = null,
)

@Serializable
data class Project(
    val schemaVersion: Int = Limits.CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val nextId: Long = 1,
    val output: OutputSettings = OutputSettings(),
    val assets: List<MediaAsset> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    /** Max clip end, 0 if there are no clips. Computed, never stored. */
    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L

    fun asset(id: String): MediaAsset? = assets.firstOrNull { it.id == id }

    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun clip(id: String): Clip? {
        for (t in tracks) {
            for (c in t.clips) if (c.id == id) return c
        }
        return null
    }

    fun trackOf(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    companion object {
        fun empty(
            id: String,
            name: String,
            output: OutputSettings = OutputSettings(),
        ): Project = Project(id = id, name = name, output = output)
    }
}
