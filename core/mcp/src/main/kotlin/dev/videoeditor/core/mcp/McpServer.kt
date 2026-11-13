package dev.videoeditor.core.mcp

import dev.videoeditor.core.mcp.bridge.FrameTooLargeException
import dev.videoeditor.core.mcp.bridge.LineReader
import dev.videoeditor.core.mcp.bridge.LineWriter
import dev.videoeditor.timeline.schema.ToolCallResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream

/**
 * A small hand-written MCP server over newline-delimited JSON-RPC 2.0 (stdio). Supports the legacy
 * `initialize` handshake (2024-11-05 .. 2025-11-25) and the stateless 2026-07-28 style (`server/discover`,
 * no handshake required, version in each request's `_meta`). stdout carries protocol messages only.
 */
class McpServer(
    private val backend: ProjectBackend,
    private val version: String = VERSION,
    private val maxMessageBytes: Int = 4 shl 20,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Serves until end of input. */
    fun run(
        input: InputStream,
        output: OutputStream,
    ) {
        val reader = LineReader(input)
        val writer = LineWriter(output)
        while (true) {
            val line =
                try {
                    reader.readLine(maxMessageBytes) ?: return
                } catch (e: FrameTooLargeException) {
                    Log.warn("dropped a message larger than $maxMessageBytes bytes")
                    writer.writeLine(error(JsonNull, INVALID_REQUEST, "Message larger than $maxMessageBytes bytes"))
                    if (!reader.skipRest()) return
                    continue
                }
            if (line.isBlank()) continue
            val reply = runBlocking { handle(line) }
            if (reply != null) writer.writeLine(reply)
        }
    }

    /** Handles one framed message; returns the reply line, or null for notifications. */
    suspend fun handle(line: String): String? {
        val msg =
            try {
                json.parseToJsonElement(line)
            } catch (e: SerializationException) {
                return error(JsonNull, PARSE_ERROR, "Parse error")
            }
        if (msg is JsonArray) return error(JsonNull, INVALID_REQUEST, "Batches are not supported")
        if (msg !is JsonObject || (msg["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
            return error(JsonNull, INVALID_REQUEST, "Invalid request")
        }
        val id = msg["id"]
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null) return null // notification (notifications/initialized, notifications/cancelled, ...)
        if (method == null) return null // a response to something we never send
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        metaVersion(params)?.let { v ->
            if (v !in SUPPORTED_VERSIONS) {
                return error(
                    id,
                    INVALID_PARAMS,
                    "Unsupported protocol version $v",
                    buildJsonObject {
                        put("supported", buildJsonArray { SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } })
                        put("requested", v)
                    },
                )
            }
        }
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "server/discover" -> result(id, discover())
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, toolsList())
                "tools/call" -> toolsCall(id, params)
                "resources/list" -> result(id, resourcesList())
                "resources/templates/list" ->
                    result(
                        id,
                        buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) },
                    )
                "resources/read" -> resourcesRead(id, params)
                "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.warn("internal error in $method: ${e.javaClass.simpleName}")
            error(id, INTERNAL_ERROR, "Internal error")
        }
    }

    private fun metaVersion(params: JsonObject): String? {
        val meta = params["_meta"] as? JsonObject ?: return null
        return meta.entries
            .firstOrNull {
                it.key.endsWith("protocolVersion")
            }?.let { (it.value as? JsonPrimitive)?.contentOrNull }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val negotiated = if (requested != null && requested in SUPPORTED_VERSIONS) requested else LEGACY_LATEST
        return buildJsonObject {
            put("protocolVersion", negotiated)
            put("capabilities", capabilities())
            put("serverInfo", serverInfo())
            put("instructions", instructions())
        }
    }

    private fun discover(): JsonObject =
        buildJsonObject {
            put("supportedVersions", buildJsonArray { SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } })
            put("capabilities", capabilities())
            put("serverInfo", serverInfo())
            put("instructions", instructions())
        }

    private fun capabilities(): JsonObject =
        buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
            put(
                "resources",
                buildJsonObject {
                    put("subscribe", false)
                    put("listChanged", false)
                },
            )
        }

    private fun serverInfo(): JsonObject =
        buildJsonObject {
            put("name", "vedit-mcp")
            put("title", "Open Video Editor")
            put("version", version)
        }

    private fun instructions(): String =
        "Edits a video project (${backend.description}). Times are in microseconds (1 s = 1000000). " +
            "Read with get_project / list_clips before editing; every edit is one undoable step on the timeline."

    private fun toolsList(): JsonObject =
        buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    for (spec in McpTools.specs) {
                        add(
                            buildJsonObject {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("inputSchema", JsonObject(spec.inputSchema - "description"))
                                put(
                                    "annotations",
                                    buildJsonObject {
                                        put("readOnlyHint", spec.name in McpTools.readOnly)
                                        put("destructiveHint", spec.name in McpTools.destructive)
                                        put("openWorldHint", false)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private suspend fun toolsCall(
        id: JsonElement,
        params: JsonObject,
    ): String {
        val name =
            (params["name"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_PARAMS, "Missing tool name")
        if (McpTools.find(name) == null) return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val args =
            when (val a = params["arguments"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> a
                else -> return error(id, INVALID_PARAMS, "arguments must be an object")
            }
        return result(id, toolResult(backend.callTool(name, args)))
    }

    private fun toolResult(r: ToolCallResult): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", r.content.toString())
                        },
                    )
                },
            )
            put("structuredContent", r.content)
            put("isError", r.isError)
        }

    private suspend fun projectId(): String? {
        val r = backend.callTool("get_project", JsonObject(emptyMap()))
        if (r.isError) return null
        return (r.content["id"] as? JsonPrimitive)?.contentOrNull
    }

    private suspend fun resourcesList(): JsonObject {
        val pid = projectId()
        return buildJsonObject {
            put(
                "resources",
                buildJsonArray {
                    if (pid != null) {
                        for ((suffix, title, mime) in RESOURCES) {
                            add(
                                buildJsonObject {
                                    put("uri", "vedit://project/$pid$suffix")
                                    put("name", "project$suffix".replace('/', '-'))
                                    put("title", title)
                                    put("mimeType", mime)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    private suspend fun resourcesRead(
        id: JsonElement,
        params: JsonObject,
    ): String {
        val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_PARAMS, "Missing uri")
        val m = Regex("^vedit://project/([^/]+)(/clips|/transcript)?$").matchEntire(uri)
        val pid = projectId()
        if (m == null || pid == null || m.groupValues[1] != pid) {
            return error(id, RESOURCE_NOT_FOUND, "Resource not found", buildJsonObject { put("uri", uri) })
        }
        val (tool, args, mime) =
            when (m.groupValues[2]) {
                "" -> Triple("get_project", buildJsonObject { put("full", true) }, "application/json")
                "/clips" -> Triple("list_clips", JsonObject(emptyMap()), "application/json")
                else -> Triple("get_transcript", JsonObject(emptyMap()), "text/plain")
            }
        val r = backend.callTool(tool, args)
        if (r.isError) return error(id, RESOURCE_NOT_FOUND, "Resource unavailable", r.content)
        val text = if (mime == "text/plain") transcriptText(r.content) else r.content.toString()
        return result(
            id,
            buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("uri", uri)
                                put("mimeType", mime)
                                put("text", text)
                            },
                        )
                    },
                )
            },
        )
    }

    /** One line per entry: `[startUs-endUs] clipId: text`. */
    private fun transcriptText(o: JsonObject): String {
        val entries = o["entries"] as? JsonArray ?: return ""
        return entries.joinToString("\n") { e ->
            val obj = e as? JsonObject ?: return@joinToString e.toString()

            fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull ?: ""
            "[${s("startUs")}-${s("endUs")}] ${s("clipId")}: ${s("text")}"
        }
    }

    companion object {
        const val VERSION = "0.1.0"
        const val LEGACY_LATEST = "2025-11-25"
        val SUPPORTED_VERSIONS = listOf("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val RESOURCE_NOT_FOUND = -32002

        private val RESOURCES =
            listOf(
                Triple("", "Project JSON", "application/json"),
                Triple("/clips", "Clip list", "application/json"),
                Triple("/transcript", "Transcript", "text/plain"),
            )

        private fun result(
            id: JsonElement,
            result: JsonObject,
        ): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()

        private fun error(
            id: JsonElement,
            code: Int,
            message: String,
            data: JsonObject? = null,
        ): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                        data?.let { put("data", it) }
                    },
                )
            }.toString()
    }
}

/** Diagnostics go to stderr only; stdout is the protocol channel. Never pass secrets here. */
object Log {
    @Volatile var quiet = false

    fun info(msg: String) {
        if (!quiet) System.err.println("vedit-mcp: $msg")
    }

    fun warn(msg: String) = System.err.println("vedit-mcp: warning: $msg")
}
