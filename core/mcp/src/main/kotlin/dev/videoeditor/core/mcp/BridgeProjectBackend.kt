package dev.videoeditor.core.mcp

import dev.videoeditor.core.mcp.bridge.BridgeClient
import dev.videoeditor.core.mcp.bridge.BridgeCredentials
import dev.videoeditor.core.mcp.bridge.BridgeEndpoint
import dev.videoeditor.core.mcp.bridge.BridgeErrors
import dev.videoeditor.core.mcp.bridge.BridgeException
import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.json.CommandDecodeResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.schema.ToolCallResult
import dev.videoeditor.timeline.schema.ToolDispatcher
import dev.videoeditor.timeline.session.CommandTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/**
 * Phone mode: every tool goes through the authenticated bridge. Reads fetch the project and run the shared
 * ToolDispatcher queries locally; edits become one `command.apply` batch with the last read revision as
 * `baseRevision` (re-read and reported on STALE_REVISION, never retried blindly).
 */
class BridgeProjectBackend(
    private val endpoint: BridgeEndpoint,
    private val credentials: () -> BridgeCredentials?,
    private val client: BridgeClient = BridgeClient(),
    private val exportPollMs: Long = 2_000,
) : ProjectBackend {
    override val description: String = "phone bridge at $endpoint"

    private var lastRevision: Long? = null
    private val queries = ToolDispatcher(null, null)

    override suspend fun callTool(
        name: String,
        args: JsonObject,
    ): ToolCallResult =
        try {
            ensureConnected()
            when {
                name == "get_project" || name == "list_clips" -> {
                    val project = readProject()
                    queries.call(ReadOnlyTarget(project), name, args)
                }
                name == "get_transcript" ->
                    ToolCallResult(
                        false,
                        client.call("transcript.get", buildJsonObject { put("query", args) }),
                    )
                name == "export" -> export(args)
                McpTools.isCommand(name) -> command(name, args)
                else -> McpTools.error("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: BridgeException) {
            if (e.code == BridgeErrors.SESSION_CLOSED) client.close()
            McpTools.error(e.name, e.message ?: e.name, e.data)
        } catch (e: IOException) {
            client.close()
            McpTools.error(
                "PHONE_UNREACHABLE",
                "Cannot reach the phone at $endpoint (${e.message}). Open the app, turn on 'Allow MCP control', " +
                    "and run " +
                    "`adb forward tcp:${BridgeEndpoint.DEFAULT_PORT} localabstract:${BridgeEndpoint.SOCKET_NAME}`.",
            )
        }

    private suspend fun ensureConnected() {
        if (client.isConnected) return
        val creds =
            credentials() ?: throw BridgeException(
                BridgeErrors.UNAUTHENTICATED,
                "Not paired with a phone. Run `vedit-mcp pair` and enter the code shown in the app.",
            )
        client.connect(endpoint, creds)
    }

    private suspend fun readProject(): Project {
        val r = client.getProject()
        lastRevision = (r["revision"] as? JsonPrimitive)?.longOrNull
        val p =
            r["project"] as? JsonObject
                ?: throw BridgeException(BridgeErrors.NOT_FOUND, "No project is open on the phone")
        return try {
            TimelineJson.json.decodeFromJsonElement(Project.serializer(), p)
        } catch (e: SerializationException) {
            throw BridgeException(
                BridgeErrors.UNSUPPORTED,
                "The app's project format is newer than this vedit-mcp; update it",
            )
        }
    }

    private suspend fun command(
        name: String,
        args: JsonObject,
    ): ToolCallResult {
        val spec = McpTools.find(name)!!
        val cmd = JsonObject(args.filterKeys { it != "type" } + ("type" to JsonPrimitive(spec.commandType!!)))
        when (val d = TimelineJson.decodeCommand(cmd)) {
            is CommandDecodeResult.Failure ->
                return ToolCallResult(
                    true,
                    buildJsonObject {
                        put("ok", false)
                        put("error", TimelineJson.encodeError(d.error))
                    },
                )
            is CommandDecodeResult.Success -> Unit
        }
        if (lastRevision == null) readProject()
        val base = lastRevision!!
        return try {
            val r = client.apply(null, base, UUID.randomUUID().toString(), name, JsonArray(listOf(cmd)))
            lastRevision = (r["revision"] as? JsonPrimitive)?.longOrNull ?: lastRevision
            ToolCallResult(false, JsonObject(mapOf("ok" to JsonPrimitive(true)) + r))
        } catch (e: BridgeException) {
            if (e.code != BridgeErrors.STALE_REVISION) throw e
            readProject()
            McpTools.error(
                "STALE_REVISION",
                "The project changed on the phone since it was last read (now revision $lastRevision); nothing was " +
                    "applied. Re-read it with get_project or list_clips and decide again.",
            )
        }
    }

    private suspend fun export(args: JsonObject): ToolCallResult {
        val jobId = (args["jobId"] as? JsonPrimitive)?.contentOrNull
        var status =
            if (jobId != null) {
                client.call("export.status", buildJsonObject { put("exportId", jobId) })
            } else {
                client.call("export.request", buildJsonObject { put("request", JsonObject(args - "jobId")) })
            }
        val id = (status["exportId"] as? JsonPrimitive)?.contentOrNull ?: jobId
        var waited = 0L
        while (id != null && state(status) == "awaiting_confirmation" && waited < exportPollMs) {
            delay(250)
            waited += 250
            status = client.call("export.status", buildJsonObject { put("exportId", id) })
        }
        val note =
            when (state(status)) {
                "awaiting_confirmation" ->
                    "Waiting for the user to confirm the export on the phone. " +
                        "Call export with jobId=$id to check again."
                "running" -> "Exporting on the phone. Call export with jobId=$id for progress."
                "denied" -> "The user declined the export on the phone."
                else -> null
            }
        val failed = state(status) in setOf("denied", "failed")
        return ToolCallResult(failed, JsonObject(status + listOfNotNull(note?.let { "note" to JsonPrimitive(it) })))
    }

    private fun state(o: JsonObject): String? = (o["state"] as? JsonPrimitive)?.contentOrNull

    private class ReadOnlyTarget(
        override val project: Project,
    ) : CommandTarget {
        override fun submit(command: Command): CommandResult = throw IllegalStateException("read-only")
    }
}
