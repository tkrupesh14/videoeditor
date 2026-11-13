package dev.videoeditor.timeline.schema

import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.host.AssetTranscript
import dev.videoeditor.timeline.host.ExportRequest
import dev.videoeditor.timeline.host.ExportService
import dev.videoeditor.timeline.host.ExportState
import dev.videoeditor.timeline.host.ExportStatus
import dev.videoeditor.timeline.host.TranscriptService
import dev.videoeditor.timeline.json.CommandDecodeResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.query.ClipView
import dev.videoeditor.timeline.query.ProjectSummary
import dev.videoeditor.timeline.query.Queries
import dev.videoeditor.timeline.query.TimelineTranscript
import dev.videoeditor.timeline.query.TranscriptMapper
import dev.videoeditor.timeline.session.CommandTarget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Result of a command tool call (design section 6.5). */
@Serializable
data class CommandResponse(
    val ok: Boolean,
    val summary: String? = null,
    val changed: Boolean? = null,
    val createdIds: List<String>? = null,
    val durationUs: Long? = null,
    val error: JsonObject? = null,
)

data class ToolCallResult(
    val isError: Boolean,
    val content: JsonObject,
)

/** Maps tool names to commands, queries and host calls. Shared by the in-app AI and the MCP server. */
class ToolDispatcher(
    private val exportService: ExportService?,
    private val transcriptService: TranscriptService?,
) {
    private val json = TimelineJson.json

    /** Never throws for bad input: unknown tools and undecodable arguments become error results. */
    suspend fun call(
        target: CommandTarget,
        name: String,
        args: JsonObject,
    ): ToolCallResult {
        val spec = ToolRegistry.find(name) ?: return error("unknown_tool", "Unknown tool: $name")
        return when (spec.kind) {
            ToolKind.COMMAND -> command(target, spec, args)
            ToolKind.QUERY, ToolKind.HOST -> other(target.project, name, args)
        }
    }

    private fun command(
        target: CommandTarget,
        spec: ToolSpec,
        args: JsonObject,
    ): ToolCallResult {
        val withType = JsonObject(args.filterKeys { it != "type" } + ("type" to JsonPrimitive(spec.commandType!!)))
        val cmd =
            when (val d = TimelineJson.decodeCommand(withType)) {
                is CommandDecodeResult.Success -> d.command
                is CommandDecodeResult.Failure -> return failure(TimelineJson.encodeError(d.error))
            }
        return when (val r = target.submit(cmd)) {
            is CommandResult.Success ->
                ToolCallResult(
                    false,
                    encode(
                        CommandResponse.serializer(),
                        CommandResponse(true, r.summary, r.changed, r.createdIds, r.project.durationUs),
                    ),
                )
            is CommandResult.Failure -> failure(TimelineJson.encodeError(r.error))
        }
    }

    private suspend fun other(
        project: Project,
        name: String,
        args: JsonObject,
    ): ToolCallResult =
        try {
            when (name) {
                "get_project" -> {
                    val a = json.decodeFromJsonElement(GetProjectArgs.serializer(), args)
                    val content =
                        if (a.full) {
                            json.encodeToJsonElement(Project.serializer(), project).jsonObject
                        } else {
                            encode(ProjectSummary.serializer(), Queries.summary(project))
                        }
                    ToolCallResult(false, content)
                }
                "list_clips" -> {
                    val a = json.decodeFromJsonElement(ListClipsArgs.serializer(), args)
                    val clips = Queries.listClips(project, a.trackId, a.fromUs, a.toUs)
                    ToolCallResult(
                        false,
                        JsonObject(
                            mapOf(
                                "clips" to json.encodeToJsonElement(ListSerializer(ClipView.serializer()), clips),
                            ),
                        ),
                    )
                }
                "export" -> export(project, json.decodeFromJsonElement(ExportArgs.serializer(), args))
                "get_transcript" ->
                    transcript(
                        project,
                        json.decodeFromJsonElement(GetTranscriptArgs.serializer(), args),
                    )
                else -> error("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: SerializationException) {
            error("malformed", "Malformed arguments for $name: ${e.message}")
        } catch (e: IllegalArgumentException) {
            error("malformed", "Malformed arguments for $name: ${e.message}")
        }

    private suspend fun export(
        project: Project,
        a: ExportArgs,
    ): ToolCallResult {
        val service = exportService ?: return error("not_available", "Export is not available in this context")
        val status: ExportStatus =
            if (a.jobId != null) {
                service.exportStatus(a.jobId) ?: return error("not_found", "No export job ${a.jobId}")
            } else {
                service.startExport(project, ExportRequest(a.fileName, a.width, a.height, a.frameRate))
            }
        return ToolCallResult(status.state == ExportState.FAILED, encode(ExportStatus.serializer(), status))
    }

    private suspend fun transcript(
        project: Project,
        a: GetTranscriptArgs,
    ): ToolCallResult {
        val service =
            transcriptService ?: return error("not_available", "Transcription is not available in this context")
        val found = LinkedHashMap<String, AssetTranscript>()
        for (assetId in TranscriptMapper.transcribableAssetIds(project)) {
            service.transcript(assetId)?.let { found[assetId] = it }
        }
        val t = TranscriptMapper.toTimeline(project, found, a.wordLevel, a.fromUs, a.toUs)
        return ToolCallResult(false, encode(TimelineTranscript.serializer(), t))
    }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject

    private fun failure(error: JsonObject): ToolCallResult =
        ToolCallResult(true, encode(CommandResponse.serializer(), CommandResponse(ok = false, error = error)))

    private fun error(
        type: String,
        message: String,
    ): ToolCallResult = failure(JsonObject(mapOf("type" to JsonPrimitive(type), "message" to JsonPrimitive(message))))
}
