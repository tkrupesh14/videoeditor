package dev.videoeditor.core.mcp

import dev.videoeditor.timeline.schema.ToolCallResult
import dev.videoeditor.timeline.schema.ToolKind
import dev.videoeditor.timeline.schema.ToolRegistry
import dev.videoeditor.timeline.schema.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the MCP tools are written against (B9). Two implementations: [BridgeProjectBackend] (phone, via the
 * authenticated bridge) and [FileProjectBackend] (offline `--project` mode, B8).
 */
interface ProjectBackend {
    /** Short description for logs and the MCP `instructions` field. */
    val description: String

    /** Only called with names from [McpTools.specs]. Never throws for bad input. */
    suspend fun callTool(
        name: String,
        args: JsonObject,
    ): ToolCallResult
}

/** The tools vedit-mcp exposes: exactly ToolRegistry.mcpV1 (deny by default for everything else). */
object McpTools {
    val specs: List<ToolSpec> = ToolRegistry.mcpV1
    private val byName = specs.associateBy { it.name }

    val readOnly: Set<String> = setOf("get_project", "list_clips", "get_transcript")
    val destructive: Set<String> = setOf("delete", "apply_cuts")

    fun find(name: String): ToolSpec? = byName[name]

    fun isCommand(name: String): Boolean = byName[name]?.kind == ToolKind.COMMAND

    /** An error result in the same shape ToolDispatcher uses: `{ok:false, error:{type, message}}`. */
    fun error(
        type: String,
        message: String,
        extra: JsonObject? = null,
    ): ToolCallResult =
        ToolCallResult(
            true,
            buildJsonObject {
                put("ok", false)
                put(
                    "error",
                    JsonObject(
                        mapOf("type" to JsonPrimitive(type), "message" to JsonPrimitive(message)) +
                            (extra ?: emptyMap()),
                    ),
                )
            },
        )
}
