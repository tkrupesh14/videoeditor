package dev.videoeditor.timeline.schema

import dev.videoeditor.timeline.command.AddCaption
import dev.videoeditor.timeline.command.AddClip
import dev.videoeditor.timeline.command.AddTrack
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.Batch
import dev.videoeditor.timeline.command.DeleteClips
import dev.videoeditor.timeline.command.EditCaption
import dev.videoeditor.timeline.command.ImportMedia
import dev.videoeditor.timeline.command.MoveClip
import dev.videoeditor.timeline.command.RemoveTrack
import dev.videoeditor.timeline.command.RippleDeleteRange
import dev.videoeditor.timeline.command.SetClipProperties
import dev.videoeditor.timeline.command.SetProjectSettings
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.command.TrimClip
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ToolKind { COMMAND, QUERY, HOST }

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val kind: ToolKind,
    /** Serial name of the command, for COMMAND tools. */
    val commandType: String? = null,
)

@Serializable
@Desc(
    "Get an overview of the project: name, duration, output settings, tracks and media assets. " +
        "With full = true, returns the complete project JSON including every clip.",
)
internal data class GetProjectArgs(
    @Desc("Return the full project JSON instead of the summary.")
    val full: Boolean = false,
)

@Serializable
@Desc(
    "List clips with their ids, tracks, timeline ranges and source ranges, optionally limited to one track " +
        "and/or a time window. Times are in microseconds.",
)
internal data class ListClipsArgs(
    @Desc("Only clips on this track.")
    val trackId: String? = null,
    @Desc("Only clips ending after this time, in microseconds (1 second = 1000000).")
    @Min(0)
    val fromUs: Long? = null,
    @Desc("Only clips starting before this time, in microseconds (1 second = 1000000).")
    @Min(0)
    val toUs: Long? = null,
)

@Serializable
@Desc(
    "Start exporting the project to a video file, or, with jobId, get the status of an export started earlier. " +
        "Size and frame rate default to the project output settings.",
)
internal data class ExportArgs(
    @Desc("Output file name without directory.")
    val fileName: String? = null,
    @Desc("Output width in pixels, even, 16..8192.")
    @Min(16)
    val width: Int? = null,
    @Desc("Output height in pixels, even, 16..8192.")
    @Min(16)
    val height: Int? = null,
    @Desc("Output frame rate, 1..120.")
    @Min(1)
    val frameRate: Int? = null,
    @Desc("Id of a running export; when given, returns its status and starts nothing.")
    val jobId: String? = null,
)

@Serializable
@Desc(
    "Get the transcript of the speech in the timeline, in timeline time (microseconds), the coordinates " +
        "apply_cuts uses. Each entry names the clip it comes from.",
)
internal data class GetTranscriptArgs(
    @Desc("Only entries ending after this time, in microseconds (1 second = 1000000).")
    @Min(0)
    val fromUs: Long? = null,
    @Desc("Only entries starting before this time, in microseconds (1 second = 1000000).")
    @Min(0)
    val toUs: Long? = null,
    @Desc("Return one entry per word instead of per segment.")
    val wordLevel: Boolean = false,
)

/** Every tool the AI layer and the MCP server can expose (design section 6.5). */
object ToolRegistry {
    private fun spec(
        name: String,
        serializer: KSerializer<*>,
        kind: ToolKind,
        commandType: String? = null,
    ): ToolSpec {
        val schema = JsonSchemaGenerator.schemaFor(serializer.descriptor)
        val description = (schema["description"] as? JsonPrimitive)?.content ?: error("$name has no @Desc")
        return ToolSpec(name, description, schema, kind, commandType)
    }

    private fun command(
        name: String,
        serializer: KSerializer<*>,
    ): ToolSpec = spec(name, serializer, ToolKind.COMMAND, serializer.descriptor.serialName)

    val mcpV1: List<ToolSpec> =
        listOf(
            spec("get_project", GetProjectArgs.serializer(), ToolKind.QUERY),
            spec("list_clips", ListClipsArgs.serializer(), ToolKind.QUERY),
            command("split", SplitClip.serializer()),
            command("trim", TrimClip.serializer()),
            command("move", MoveClip.serializer()),
            command("delete", DeleteClips.serializer()),
            command("add_caption", AddCaption.serializer()),
            command("apply_cuts", ApplyCuts.serializer()),
            spec("export", ExportArgs.serializer(), ToolKind.HOST),
            spec("get_transcript", GetTranscriptArgs.serializer(), ToolKind.HOST),
        )

    val all: List<ToolSpec> =
        mcpV1 +
            listOf(
                command("add_clip", AddClip.serializer()),
                command("ripple_delete_range", RippleDeleteRange.serializer()),
                command("set_clip_properties", SetClipProperties.serializer()),
                command("edit_caption", EditCaption.serializer()),
                command("add_track", AddTrack.serializer()),
                command("remove_track", RemoveTrack.serializer()),
                command("import_media", ImportMedia.serializer()),
                command("set_project_settings", SetProjectSettings.serializer()),
                command("batch", Batch.serializer()),
            )

    private val byName = all.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]
}
