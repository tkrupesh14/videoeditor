package dev.videoeditor.core.mcp.bridge

import dev.videoeditor.timeline.command.Command
import dev.videoeditor.timeline.command.CommandResult
import dev.videoeditor.timeline.host.TranscriptService
import dev.videoeditor.timeline.json.CommandDecodeResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.model.Project
import dev.videoeditor.timeline.schema.ToolDispatcher
import dev.videoeditor.timeline.session.CommandTarget
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.timeline.session.HistoryEntry
import dev.videoeditor.timeline.session.Origin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Export states on the wire (B5). */
enum class RemoteExportState(
    val wire: String,
) {
    AWAITING_CONFIRMATION("awaiting_confirmation"),
    RUNNING("running"),
    DONE("done"),
    DENIED("denied"),
    FAILED("failed"),
    ;

    val terminal: Boolean get() = this == DONE || this == DENIED || this == FAILED
}

data class ExportPrompt(
    val exportId: String,
    val clientName: String,
    val projectName: String,
    val request: JsonObject,
)

/**
 * Remote export requests (B6): a request only raises a prompt; nothing runs until the user allows it on the
 * phone, every time. One request pending or running at a time.
 */
class ExportGate(
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(
        val prompt: ExportPrompt,
        var state: RemoteExportState,
        var progress: Double? = null,
        var error: String? = null,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val _prompt = MutableStateFlow<ExportPrompt?>(null)

    /** The request the user must answer, if any. */
    val prompt: StateFlow<ExportPrompt?> = _prompt.asStateFlow()

    @Synchronized
    fun request(
        clientName: String,
        projectName: String,
        request: JsonObject,
    ): JsonObject {
        if (entries.values.any { !it.state.terminal }) {
            throw BridgeException(BridgeErrors.RATE_LIMITED, "Another export request is already pending or running")
        }
        val id = "x_" + BridgeCrypto.b64url(BridgeCrypto.randomBytes(9, random))
        val p = ExportPrompt(id, clientName, projectName, request)
        entries[id] = Entry(p, RemoteExportState.AWAITING_CONFIRMATION)
        while (entries.size > 16) entries.remove(entries.keys.first())
        _prompt.value = p
        return statusLocked(id)
    }

    /** The user's answer. Returns the prompt when the export should now start, else null. */
    @Synchronized
    fun answer(
        exportId: String,
        allow: Boolean,
    ): ExportPrompt? {
        val e = entries[exportId] ?: return null
        if (_prompt.value?.exportId == exportId) _prompt.value = null
        if (e.state != RemoteExportState.AWAITING_CONFIRMATION) return null
        e.state = if (allow) RemoteExportState.RUNNING else RemoteExportState.DENIED
        return if (allow) e.prompt else null
    }

    @Synchronized
    fun update(
        exportId: String,
        state: RemoteExportState,
        progress: Double? = null,
        error: String? = null,
    ) {
        val e = entries[exportId] ?: return
        if (e.state.terminal) return
        e.state = state
        e.progress = progress
        e.error = error
    }

    /** Denies whatever is still waiting (bridge stopped, app backgrounded). */
    @Synchronized
    fun denyPending() {
        entries.values.filter { it.state == RemoteExportState.AWAITING_CONFIRMATION }.forEach {
            it.state =
                RemoteExportState.DENIED
        }
        _prompt.value = null
    }

    @Synchronized
    fun status(exportId: String): JsonObject = statusLocked(exportId)

    private fun statusLocked(exportId: String): JsonObject {
        val e = entries[exportId] ?: throw BridgeException(BridgeErrors.NOT_FOUND, "No export $exportId")
        return buildJsonObject {
            put("exportId", exportId)
            put("state", e.state.wire)
            e.progress?.let { put("progress", it) }
            e.error?.let { put("error", it) }
        }
    }
}

/**
 * [BridgeCommandSink] over the editor's one [EditorSession] (B5 semantics): atomic batches committed as one
 * undo entry with origin MCP, revision check, no editing logic of its own. Session access runs in
 * [sessionContext] (the main thread in the app). Pure JVM so it is unit-tested.
 */
class SessionBridgeSink(
    private val session: EditorSession,
    /** Non-null while edits must be refused (e.g. an AI suggestion is pending); the text is the reason. */
    private val readOnlyReason: () -> String?,
    private val exportGate: ExportGate,
    private val transcriptService: TranscriptService? = null,
    private val sessionContext: CoroutineContext = EmptyCoroutineContext,
) : BridgeCommandSink {
    private val json = TimelineJson.json
    private val mcpEntries = HashMap<String, HistoryEntry>()

    private fun redacted(p: Project): JsonObject {
        val safe = p.copy(assets = p.assets.map { it.copy(uri = "redacted:${it.id}") })
        return json.encodeToJsonElement(Project.serializer(), safe).jsonObject
    }

    private fun checkProject(projectId: String?) {
        if (projectId != null && projectId != session.project.id) {
            throw BridgeException(BridgeErrors.NOT_FOUND, "Project $projectId is not open on the phone")
        }
    }

    override suspend fun listProjects(): JsonObject =
        withContext(sessionContext) {
            val p = session.project
            buildJsonObject {
                put(
                    "projects",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("projectId", p.id)
                                put("name", p.name)
                                put("revision", session.revision)
                                put("durationMs", p.durationUs / 1000)
                            },
                        )
                    },
                )
            }
        }

    override suspend fun getProject(projectId: String?): JsonObject =
        withContext(sessionContext) {
            checkProject(projectId)
            val p = session.project
            buildJsonObject {
                put("projectId", p.id)
                put("revision", session.revision)
                put("project", redacted(p))
            }
        }

    override suspend fun getTranscript(
        projectId: String?,
        query: JsonObject,
    ): JsonObject {
        val project = withContext(sessionContext) { checkProject(projectId).let { session.project } }
        val r = ToolDispatcher(null, transcriptService).call(ReadOnly(project), "get_transcript", query)
        if (r.isError) {
            val msg =
                ((r.content["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
                    ?: "Transcript unavailable"
            throw BridgeException(BridgeErrors.UNSUPPORTED, msg)
        }
        return r.content
    }

    private fun decode(commands: JsonArray): List<Command> {
        val problems = mutableListOf<String>()
        val out =
            commands.mapIndexedNotNull { i, c ->
                when (val d = TimelineJson.decodeCommand(c as? JsonObject ?: JsonObject(emptyMap()))) {
                    is CommandDecodeResult.Success -> d.command
                    is CommandDecodeResult.Failure -> {
                        problems += "commands[$i]: ${d.error.message}"
                        null
                    }
                }
            }
        if (problems.isNotEmpty()) throw rejected(problems)
        return out
    }

    private fun rejected(problems: List<String>): BridgeException =
        BridgeException(
            BridgeErrors.COMMAND_REJECTED,
            "Command rejected: ${problems.first()}",
            buildJsonObject { put("problems", buildJsonArray { problems.forEach { add(JsonPrimitive(it)) } }) },
        )

    override suspend fun validate(
        projectId: String?,
        commands: JsonArray,
    ): JsonObject =
        withContext(sessionContext) {
            checkProject(projectId)
            val problems = mutableListOf<String>()
            try {
                val preview = session.newPreview()
                decode(commands).forEachIndexed { i, c ->
                    val r = preview.submit(c)
                    if (r is CommandResult.Failure) problems += "commands[$i]: ${r.error.message}"
                }
            } catch (e: BridgeException) {
                problems += e.message ?: "invalid"
            }
            buildJsonObject {
                put("ok", problems.isEmpty())
                put("problems", buildJsonArray { problems.forEach { add(JsonPrimitive(it)) } })
            }
        }

    override suspend fun apply(
        projectId: String?,
        baseRevision: Long,
        label: String,
        client: PairedClient,
        commands: JsonArray,
    ): JsonObject =
        withContext(sessionContext) {
            checkProject(projectId)
            readOnlyReason()?.let { throw BridgeException(BridgeErrors.COMMAND_REJECTED, it) }
            if (session.state.transactionOpen) {
                throw BridgeException(
                    BridgeErrors.COMMAND_REJECTED,
                    "The user is editing on the phone right now; try again shortly",
                )
            }
            if (session.revision != baseRevision) {
                throw BridgeException(
                    BridgeErrors.STALE_REVISION,
                    "The project changed (revision ${session.revision}, expected $baseRevision); nothing was applied",
                    buildJsonObject { put("revision", session.revision) },
                )
            }
            val decoded = decode(commands)
            val preview = session.newPreview()
            val results =
                decoded.mapIndexed { i, c ->
                    when (val r = preview.submit(c)) {
                        is CommandResult.Failure ->
                            throw BridgeException(
                                BridgeErrors.COMMAND_REJECTED,
                                "commands[$i]: ${r.error.message}; nothing was applied",
                                buildJsonObject { put("error", TimelineJson.encodeError(r.error)) },
                            )
                        is CommandResult.Success ->
                            buildJsonObject {
                                put("summary", r.summary)
                                put("changed", r.changed)
                                put("createdIds", buildJsonArray { r.createdIds.forEach { add(JsonPrimitive(it)) } })
                            }
                    }
                }
            when (val c = session.commitPreview(preview, label, Origin.MCP)) {
                is CommandResult.Failure -> throw BridgeException(BridgeErrors.STALE_REVISION, c.error.message)
                is CommandResult.Success ->
                    if (c.changed) {
                        session.undoHistory.lastOrNull()?.let {
                            mcpEntries[client.clientId] =
                                it
                        }
                    }
            }
            buildJsonObject {
                put("revision", session.revision)
                put("undoDepth", session.undoHistory.size)
                put("durationUs", session.project.durationUs)
                put("results", JsonArray(results))
            }
        }

    override suspend fun undo(
        projectId: String?,
        client: PairedClient,
    ): JsonObject =
        withContext(sessionContext) {
            checkProject(projectId)
            readOnlyReason()?.let { throw BridgeException(BridgeErrors.COMMAND_REJECTED, it) }
            val top = session.undoHistory.lastOrNull()
            if (top == null || top !== mcpEntries[client.clientId]) {
                throw BridgeException(
                    BridgeErrors.COMMAND_REJECTED,
                    "The last change on the phone was not made by this client",
                )
            }
            session.undo()
            mcpEntries.remove(client.clientId)
            buildJsonObject { put("revision", session.revision) }
        }

    override suspend fun requestExport(
        projectId: String?,
        request: JsonObject,
        requestedBy: PairedClient,
    ): JsonObject {
        val name = withContext(sessionContext) { checkProject(projectId).let { session.project.name } }
        return exportGate.request(requestedBy.name, name, request)
    }

    override suspend fun exportStatus(exportId: String): JsonObject = exportGate.status(exportId)

    private class ReadOnly(
        override val project: Project,
    ) : CommandTarget {
        override fun submit(command: Command): CommandResult = throw IllegalStateException("read-only")
    }
}
