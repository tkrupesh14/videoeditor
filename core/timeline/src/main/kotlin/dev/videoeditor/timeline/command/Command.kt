package dev.videoeditor.timeline.command

import dev.videoeditor.timeline.model.CaptionStyle
import dev.videoeditor.timeline.model.CaptionStylePatch
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.model.TrackKind
import dev.videoeditor.timeline.model.Transform
import dev.videoeditor.timeline.model.TransformPatch
import dev.videoeditor.timeline.schema.Desc
import dev.videoeditor.timeline.schema.Min
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One edit operation. JSON discriminator `"type"`, snake_case serial names. */
@Serializable
sealed interface Command

@Serializable
enum class ClipEdge { START, END }

@Serializable
@SerialName("import_media")
@Desc(
    "Register a media file as an asset. The app probes the file and supplies its metadata. " +
        "Importing a uri that is already registered changes nothing.",
)
data class ImportMedia(
    @Desc("Content or file uri of the media.")
    val uri: String,
    @Desc("Media duration, in microseconds (1 second = 1000000).")
    @Min(1)
    val durationUs: Long,
    @Desc("Whether the media has a video stream.")
    val hasVideo: Boolean,
    @Desc("Whether the media has an audio stream.")
    val hasAudio: Boolean,
    @Desc("Video width in pixels; required for video.")
    @Min(1)
    val width: Int? = null,
    @Desc("Video height in pixels; required for video.")
    @Min(1)
    val height: Int? = null,
    @Desc("Native frame rate of the video.")
    val frameRate: Double? = null,
    @Desc("Name to show for the asset.")
    val displayName: String? = null,
) : Command

@Serializable
@SerialName("add_track")
@Desc("Add a track. Tracks are layered: index 0 is the bottom layer, the last track is on top.")
data class AddTrack(
    @Desc("Kind of track.")
    val kind: TrackKind,
    @Desc("Track name; default is '<Kind> <n>'.")
    val name: String? = null,
    @Desc("Position in the track list (0 = bottom); omit to add on top.")
    @Min(0)
    val index: Int? = null,
) : Command

@Serializable
@SerialName("remove_track")
@Desc("Remove a track. Fails if the track has clips unless deleteClips is true.")
data class RemoveTrack(
    @Desc("Id of the track to remove.")
    val trackId: String,
    @Desc("Also delete the clips on the track.")
    val deleteClips: Boolean = false,
) : Command

@Serializable
@SerialName("add_clip")
@Desc("Place a range of an asset on a media track. The new clip id is returned in createdIds.")
data class AddClip(
    @Desc("Id of the target track (VIDEO, OVERLAY or AUDIO).")
    val trackId: String,
    @Desc("Id of the asset to place.")
    val assetId: String,
    @Desc("Timeline start, in microseconds (1 second = 1000000); omit to append after the track's last clip.")
    @Min(0)
    val startUs: Long? = null,
    @Desc("Source in point within the asset, in microseconds (1 second = 1000000).")
    @Min(0)
    val sourceInUs: Long = 0,
    @Desc("Source out point within the asset, in microseconds (1 second = 1000000); omit for the asset end.")
    @Min(0)
    val sourceOutUs: Long? = null,
    @Desc("Playback speed, 0.1..10, rounded to 0.01.")
    val speed: Double = 1.0,
    @Desc("Volume, 0..2 (1 = original).")
    val volume: Double = 1.0,
    @Desc("Placement in the frame.")
    val transform: Transform = Transform(),
    @Desc("Insert: shift later clips on the track right to make room instead of requiring free space.")
    val ripple: Boolean = false,
) : Command

@Serializable
@SerialName("split_clip")
@Desc(
    "Split clips at a timeline time. Each listed clip must strictly contain atUs. " +
        "The left part keeps its id; the new right-part ids are returned in createdIds.",
)
data class SplitClip(
    @Desc("Ids of the clips to split.")
    val clipIds: List<String>,
    @Desc("Timeline time of the cut, in microseconds (1 second = 1000000).")
    @Min(0)
    val atUs: Long,
) : Command

@Serializable
@SerialName("trim_clip")
@Desc(
    "Move the start or end edge of one clip to a timeline time. With ripple, later clips on the same track " +
        "shift to keep their distance; without ripple the edge must not run into a neighbour.",
)
data class TrimClip(
    @Desc("Id of the clip to trim.")
    val clipId: String,
    @Desc("Which edge to move.")
    val edge: ClipEdge,
    @Desc("Timeline time the edge moves to, in pre-edit coordinates, in microseconds (1 second = 1000000).")
    @Min(0)
    val toUs: Long,
    @Desc("Shift later clips on the same track by the length change.")
    val ripple: Boolean = false,
) : Command

@Serializable
@SerialName("move_clip")
@Desc(
    "Move clips as a rigid group to a new start time and/or another track. " +
        "The destination must be free; nothing is pushed aside.",
)
data class MoveClip(
    @Desc("Ids of the clips to move.")
    val clipIds: List<String>,
    @Desc("New start of the earliest listed clip, in microseconds (1 second = 1000000); omit to keep times.")
    @Min(0)
    val toStartUs: Long? = null,
    @Desc("Target track id; all listed clips must be on one track. Omit to keep tracks.")
    val toTrackId: String? = null,
) : Command

@Serializable
@SerialName("delete_clips")
@Desc("Delete clips. With ripple, later clips on the same track move left to close the deleted space.")
data class DeleteClips(
    @Desc("Ids of the clips to delete.")
    val clipIds: List<String>,
    @Desc("Close the gaps left by the deleted clips on their tracks.")
    val ripple: Boolean = false,
) : Command

@Serializable
@SerialName("ripple_delete_range")
@Desc("Remove a time range from the given tracks (all tracks if omitted) and close the gap.")
data class RippleDeleteRange(
    @Desc("Range start, in microseconds (1 second = 1000000).")
    @Min(0)
    val startUs: Long,
    @Desc("Range end (exclusive), in microseconds (1 second = 1000000).")
    @Min(0)
    val endUs: Long,
    @Desc("Ids of the tracks to cut; omit for all tracks.")
    val trackIds: List<String>? = null,
) : Command

@Serializable
@SerialName("set_clip_properties")
@Desc(
    "Change speed, volume and/or transform of clips. A speed change keeps the source range and changes " +
        "the clip duration; with ripple later clips on the same track shift accordingly.",
)
data class SetClipProperties(
    @Desc("Ids of the clips to change.")
    val clipIds: List<String>,
    @Desc("New speed, 0.1..10, rounded to 0.01 (media clips only).")
    val speed: Double? = null,
    @Desc("New volume, 0..2 (media clips only).")
    val volume: Double? = null,
    @Desc("Transform fields to change.")
    val transform: TransformPatch? = null,
    @Desc("When the speed changes, shift later clips on the same track by the duration change.")
    val ripple: Boolean = false,
) : Command

@Serializable
@Desc("One caption: a time range and its text.")
data class CaptionSpec(
    @Desc("Caption start, in microseconds (1 second = 1000000).")
    @Min(0)
    val startUs: Long,
    @Desc("Caption end (exclusive), in microseconds (1 second = 1000000).")
    @Min(0)
    val endUs: Long,
    @Desc("Caption text, at most 2000 characters.")
    val text: String,
)

@Serializable
@SerialName("add_caption")
@Desc(
    "Add captions to a caption track (the first caption track if trackId is omitted; one is created if " +
        "there is none). Captions must not overlap each other or existing captions. New ids are returned in createdIds.",
)
data class AddCaption(
    @Desc("Captions to add.")
    val captions: List<CaptionSpec>,
    @Desc("Id of the caption track; omit for the first caption track.")
    val trackId: String? = null,
    @Desc("Text style for all added captions.")
    val style: CaptionStyle = CaptionStyle(),
    @Desc("Placement for all added captions; default is the lower third.")
    val transform: Transform = Transform(translateY = 0.35),
) : Command

@Serializable
@SerialName("edit_caption")
@Desc("Change the text of one caption, or apply a style patch to several captions.")
data class EditCaption(
    @Desc("Ids of the caption clips. Only one id is allowed when text is given.")
    val clipIds: List<String>,
    @Desc("New caption text.")
    val text: String? = null,
    @Desc("Style fields to change.")
    val style: CaptionStylePatch? = null,
) : Command

@Serializable
@SerialName("apply_cuts")
@Desc(
    "Remove time ranges from every track and close the gaps. All ranges are in the current (pre-edit) " +
        "timeline coordinates; earlier cuts do not shift later ranges. Fragments shorter than 10 ms are dropped.",
)
data class ApplyCuts(
    @Desc("Time ranges to remove.")
    val ranges: List<TimeRange>,
) : Command

@Serializable
@SerialName("set_project_settings")
@Desc("Change the project name or output settings. Clips are not rescaled.")
data class SetProjectSettings(
    @Desc("New project name.")
    val name: String? = null,
    @Desc("Output width in pixels, even, 16..8192.")
    @Min(16)
    val width: Int? = null,
    @Desc("Output height in pixels, even, 16..8192.")
    @Min(16)
    val height: Int? = null,
    @Desc("Output frame rate, 1..120.")
    @Min(1)
    val frameRate: Int? = null,
) : Command

@Serializable
@SerialName("batch")
@Desc(
    "Run several commands atomically as one undo step. Each command sees the result of the previous one; " +
        "if any fails, nothing is applied. Batches cannot be nested.",
)
data class Batch(
    @Desc("Commands to run in order. Each is an object with a 'type' field naming the command.")
    val commands: List<Command>,
    @Desc("Label for the undo history.")
    val label: String? = null,
) : Command

/** Serial name of a command, e.g. "split_clip". */
val Command.typeName: String
    get() =
        when (this) {
            is ImportMedia -> "import_media"
            is AddTrack -> "add_track"
            is RemoveTrack -> "remove_track"
            is AddClip -> "add_clip"
            is SplitClip -> "split_clip"
            is TrimClip -> "trim_clip"
            is MoveClip -> "move_clip"
            is DeleteClips -> "delete_clips"
            is RippleDeleteRange -> "ripple_delete_range"
            is SetClipProperties -> "set_clip_properties"
            is AddCaption -> "add_caption"
            is EditCaption -> "edit_caption"
            is ApplyCuts -> "apply_cuts"
            is SetProjectSettings -> "set_project_settings"
            is Batch -> "batch"
        }

/** All command type names, for allowlists (e.g. the MCP bridge's deny-by-default list). */
object CommandTypes {
    val ALL: List<String> =
        listOf(
            "import_media",
            "add_track",
            "remove_track",
            "add_clip",
            "split_clip",
            "trim_clip",
            "move_clip",
            "delete_clips",
            "ripple_delete_range",
            "set_clip_properties",
            "add_caption",
            "edit_caption",
            "apply_cuts",
            "set_project_settings",
            "batch",
        )

    /** The command's type plus, for a batch, the types of its members. Useful for allowlist checks. */
    fun typesIn(command: Command): Set<String> =
        if (command is Batch) {
            setOf(command.typeName) + command.commands.flatMap { typesIn(it) }
        } else {
            setOf(command.typeName)
        }
}
