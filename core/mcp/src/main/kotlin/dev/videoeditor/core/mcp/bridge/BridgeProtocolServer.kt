package dev.videoeditor.core.mcp.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One accepted byte stream: an Android LocalSocket on the phone, a java.net.Socket in tests. */
interface BridgeConnection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** 0 = no timeout. Reads that time out throw an IOException. */
    fun setReadTimeoutMs(ms: Int)
}

class JavaSocketConnection(
    private val socket: Socket,
) : BridgeConnection {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override fun setReadTimeoutMs(ms: Int) {
        socket.soTimeout = ms
    }

    override fun close() = socket.close()
}

/** The only thing the bridge may call to touch projects (B9). Implemented in :app over :core:timeline. */
interface BridgeCommandSink {
    suspend fun listProjects(): JsonObject

    /** `{projectId, revision, project}`; asset URIs must already be redacted. */
    suspend fun getProject(projectId: String?): JsonObject

    suspend fun getTranscript(
        projectId: String?,
        query: JsonObject,
    ): JsonObject

    suspend fun validate(
        projectId: String?,
        commands: JsonArray,
    ): JsonObject

    /** Atomic: all commands or none, one undo entry labelled [label]. Throws [BridgeException] on rejection. */
    suspend fun apply(
        projectId: String?,
        baseRevision: Long,
        label: String,
        client: PairedClient,
        commands: JsonArray,
    ): JsonObject

    suspend fun undo(
        projectId: String?,
        client: PairedClient,
    ): JsonObject

    /** Never starts an export: only asks the user. */
    suspend fun requestExport(
        projectId: String?,
        request: JsonObject,
        requestedBy: PairedClient,
    ): JsonObject

    suspend fun exportStatus(exportId: String): JsonObject
}

data class BridgeLimits(
    val firstMessageBytes: Int = 4096,
    val firstMessageTimeoutMs: Int = 5_000,
    val maxUnauthenticated: Int = 4,
    val requestBytes: Int = 1 shl 20,
    val responseBytes: Int = 8 shl 20,
    val appliesPerMinute: Int = 60,
    val commandsPerBatch: Int = 200,
    val captionChars: Int = 2_000,
    val requestIdMemory: Int = 256,
    val requestIdTtlMs: Long = 10 * 60_000,
    val labelChars: Int = 120,
)

data class BridgeSession(
    val sessionId: Long,
    val client: PairedClient,
    val connectedAtEpochMs: Long,
    val lastCommandLabel: String?,
)

enum class CloseReason(
    val wire: String,
) {
    REVOKED("revoked"),
    KILLED("killed"),
    APP_STOPPED("app_stopped"),
}

/**
 * The phone side of the bridge protocol (B5, B6), independent of sockets and Android. [serve] runs one
 * connection to completion on the calling thread (blocking reads); call it from an IO dispatcher.
 */
class BridgeProtocolServer(
    private val store: ClientStore,
    private val verifier: TokenVerifier,
    private val pairing: PairingManager?,
    private val sink: BridgeCommandSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val appVersion: String = "0.1.0",
    private val limits: BridgeLimits = BridgeLimits(),
    /** Called after each applied batch (UI pulse / snackbar). */
    private val onApplied: (PairedClient, String) -> Unit = { _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class Live(
        val session: BridgeSession,
        val conn: BridgeConnection,
        val writer: LineWriter,
    )

    private val ids = AtomicLong()
    private val unauthenticated = AtomicInteger()
    private val live = ConcurrentHashMap<Long, Live>()
    private val pendingConns = ConcurrentHashMap.newKeySet<BridgeConnection>()
    private val _sessions = MutableStateFlow<List<BridgeSession>>(emptyList())
    val sessions: StateFlow<List<BridgeSession>> = _sessions.asStateFlow()

    private val applyTimes = HashMap<String, ArrayDeque<Long>>()
    private val requestCache = HashMap<String, LinkedHashMap<String, Pair<Long, JsonObject>>>()

    suspend fun serve(conn: BridgeConnection) {
        if (unauthenticated.incrementAndGet() > limits.maxUnauthenticated || verifier.globallyThrottled()) {
            unauthenticated.decrementAndGet()
            runCatching { conn.close() }
            return
        }
        pendingConns += conn
        var counted = true
        var liveId: Long? = null
        try {
            val writer = LineWriter(conn.output)
            val reader = LineReader(conn.input)
            val nonceS = verifier.newChallenge()
            writer.writeLine(
                notification(
                    "bridge.challenge",
                    buildJsonObject {
                        put("protocol", PROTOCOL)
                        put("bridgeId", store.bridgeId)
                        put("nonceS", BridgeCrypto.b64url(nonceS))
                        put("pairingOpen", pairing?.pairing?.value is PairingState.Offering)
                    },
                ),
            )
            conn.setReadTimeoutMs(limits.firstMessageTimeoutMs)
            val first =
                try {
                    reader.readLine(limits.firstMessageBytes)
                } catch (e: IOException) {
                    null
                } ?: return
            val msg = parseObject(first) ?: return
            val id = msg["id"] ?: return
            val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
            when ((msg["method"] as? JsonPrimitive)?.contentOrNull) {
                "bridge.pair" -> {
                    handlePair(writer, id, params)
                    return
                }
                "bridge.auth" -> {
                    val client = authenticate(writer, id, params, nonceS) ?: return
                    conn.setReadTimeoutMs(0)
                    unauthenticated.decrementAndGet()
                    counted = false
                    pendingConns -= conn
                    val sid = ids.incrementAndGet()
                    liveId = sid
                    live[sid] = Live(BridgeSession(sid, client, clock(), null), conn, writer)
                    publish()
                    loop(sid, reader, writer)
                }
                else -> return
            }
        } catch (e: IOException) {
            // Connection dropped or closed by stop()/revoke().
        } finally {
            if (counted) unauthenticated.decrementAndGet()
            pendingConns -= conn
            liveId?.let {
                live.remove(it)
                publish()
            }
            runCatching { conn.close() }
        }
    }

    private suspend fun handlePair(
        writer: LineWriter,
        id: JsonElement,
        params: JsonObject,
    ) {
        val p = pairing
        val code = params.str("code")
        val name = params.str("clientName")?.take(DefaultPairingManager.MAX_NAME) ?: "unnamed client"
        if (p == null || code == null) {
            writer.writeLine(error(id, BridgeErrors.UNAUTHENTICATED, "Pairing is not open on the phone"))
            return
        }
        val reply =
            when (val r = p.redeem(code, name)) {
                is RedeemResult.Paired ->
                    response(
                        id,
                        buildJsonObject {
                            put("bridgeId", r.credentials.bridgeId)
                            put("clientId", r.credentials.clientId)
                            r.credentials.token.use { put("token", it) }
                            put("scopes", scopesJson(r.credentials.scopes))
                        },
                    )
                RedeemResult.WrongCode -> error(id, BridgeErrors.UNAUTHENTICATED, "Wrong pairing code")
                RedeemResult.NotOffering ->
                    error(
                        id,
                        BridgeErrors.UNAUTHENTICATED,
                        "Pairing is not open on the phone (open 'Pair a new client')",
                    )
                RedeemResult.Denied -> error(id, BridgeErrors.USER_DENIED, "Pairing was not allowed on the phone")
                RedeemResult.TooManyClients ->
                    error(id, BridgeErrors.RATE_LIMITED, "Too many paired clients; revoke one on the phone first")
                is RedeemResult.LockedOut ->
                    error(
                        id,
                        BridgeErrors.RATE_LIMITED,
                        "Pairing is locked for ${r.retryAfterMs / 1000} s after wrong codes",
                    )
            }
        writer.writeLine(reply)
    }

    private suspend fun authenticate(
        writer: LineWriter,
        id: JsonElement,
        params: JsonObject,
        nonceS: ByteArray,
    ): PairedClient? {
        val clientId = params.str("clientId")
        val nonceC = params.str("nonceC")?.let(BridgeCrypto::unb64url)
        val proof = params.str("proof")?.let(BridgeCrypto::unb64url)
        val protocol = (params["protocol"] as? JsonPrimitive)?.longOrNull
        if (clientId == null || nonceC == null || proof == null || protocol != PROTOCOL.toLong()) {
            writer.writeLine(error(id, BridgeErrors.UNAUTHENTICATED, "Authentication failed"))
            return null
        }
        return when (val r = verifier.verify(clientId, nonceS, nonceC, proof)) {
            is TokenVerifier.VerifyResult.Accepted -> {
                writer.writeLine(
                    response(
                        id,
                        buildJsonObject {
                            put("serverProof", BridgeCrypto.b64url(r.serverProof))
                            put("scopes", scopesJson(r.client.scopes))
                            put("commandSchema", 1)
                            put("appVersion", appVersion)
                        },
                    ),
                )
                r.client
            }
            TokenVerifier.VerifyResult.Rejected -> {
                writer.writeLine(error(id, BridgeErrors.UNAUTHENTICATED, "Authentication failed"))
                null
            }
            is TokenVerifier.VerifyResult.Throttled -> {
                writer.writeLine(error(id, BridgeErrors.RATE_LIMITED, "Too many failed attempts; retry later"))
                null
            }
        }
    }

    private suspend fun loop(
        sid: Long,
        reader: LineReader,
        writer: LineWriter,
    ) {
        while (true) {
            val line =
                try {
                    reader.readLine(limits.requestBytes) ?: return
                } catch (e: FrameTooLargeException) {
                    writer.writeLine(
                        error(
                            JsonNull,
                            BridgeErrors.PAYLOAD_TOO_LARGE,
                            "Request larger than ${limits.requestBytes} bytes",
                        ),
                    )
                    return
                }
            if (line.isBlank()) continue
            val msg = parseObject(line)
            if (msg == null) {
                writer.writeLine(error(JsonNull, PARSE_ERROR, "Parse error"))
                continue
            }
            val id = msg["id"] ?: continue // notifications from the client are ignored
            val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
            val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
            val l = live[sid] ?: return
            val out =
                try {
                    response(id, dispatch(l.session.client, sid, method, params))
                } catch (e: BridgeException) {
                    error(id, e.code, e.message ?: e.name, e.data)
                } catch (e: SerializationException) {
                    error(id, INVALID_PARAMS, "Invalid params")
                } catch (e: IllegalArgumentException) {
                    error(id, INVALID_PARAMS, e.message ?: "Invalid params")
                }
            val bytes = out.toByteArray(Charsets.UTF_8).size
            writer.writeLine(
                if (bytes > limits.responseBytes) {
                    error(id, BridgeErrors.PAYLOAD_TOO_LARGE, "Response larger than ${limits.responseBytes} bytes")
                } else {
                    out
                },
            )
        }
    }

    private suspend fun dispatch(
        client: PairedClient,
        sid: Long,
        method: String?,
        p: JsonObject,
    ): JsonObject {
        fun need(scope: BridgeScope) {
            if (scope !in client.scopes) {
                throw BridgeException(
                    BridgeErrors.FORBIDDEN_SCOPE,
                    "This client was not granted the '${scope.wire}' scope",
                )
            }
        }
        val projectId = p.str("projectId")
        return when (method) {
            "ping" -> JsonObject(emptyMap())
            "project.list" -> {
                need(BridgeScope.READ)
                sink.listProjects()
            }
            "project.get" -> {
                need(BridgeScope.READ)
                sink.getProject(projectId)
            }
            "transcript.get" -> {
                need(BridgeScope.READ)
                sink.getTranscript(projectId, p["query"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            "command.validate" -> {
                need(BridgeScope.READ)
                val commands = checkCommands(p)
                sink.validate(projectId, commands)
            }
            "command.apply" -> {
                need(BridgeScope.EDIT)
                apply(client, sid, projectId, p)
            }
            "history.undo" -> {
                need(BridgeScope.EDIT)
                sink.undo(projectId, client)
            }
            "export.request" -> {
                need(BridgeScope.EXPORT)
                sink.requestExport(projectId, p["request"] as? JsonObject ?: JsonObject(emptyMap()), client)
            }
            "export.status" -> {
                need(BridgeScope.EXPORT)
                sink.exportStatus(p.str("exportId") ?: throw IllegalArgumentException("exportId is required"))
            }
            else -> throw BridgeException(METHOD_NOT_FOUND, "Method not found")
        }
    }

    private suspend fun apply(
        client: PairedClient,
        sid: Long,
        projectId: String?,
        p: JsonObject,
    ): JsonObject {
        val requestId = p.str("requestId") ?: throw IllegalArgumentException("requestId is required")
        val baseRevision =
            (p["baseRevision"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("baseRevision is required")
        val label = (p.str("label") ?: "edit").replace('\n', ' ').take(limits.labelChars)
        val now = clock()
        synchronized(requestCache) {
            val cache = requestCache.getOrPut(client.clientId) { LinkedHashMap() }
            cache.entries.removeAll { now - it.value.first > limits.requestIdTtlMs }
            cache[requestId]?.let { return it.second }
        }
        val commands = checkCommands(p)
        synchronized(applyTimes) {
            val q = applyTimes.getOrPut(client.clientId) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
            if (q.size >= limits.appliesPerMinute) {
                throw BridgeException(BridgeErrors.RATE_LIMITED, "At most ${limits.appliesPerMinute} edits per minute")
            }
            q.addLast(now)
        }
        val fullLabel = "Claude (MCP): $label"
        val result = sink.apply(projectId, baseRevision, fullLabel, client, commands)
        synchronized(requestCache) {
            val cache = requestCache.getOrPut(client.clientId) { LinkedHashMap() }
            cache[requestId] = now to result
            while (cache.size > limits.requestIdMemory) cache.remove(cache.keys.first())
        }
        live[sid]?.let { l ->
            live[sid] = Live(l.session.copy(lastCommandLabel = label), l.conn, l.writer)
            publish()
        }
        onApplied(client, label)
        return result
    }

    /** Deny by default: every command must be an allowlisted type, within the batch and caption limits. */
    private fun checkCommands(p: JsonObject): JsonArray {
        val commands = p["commands"] as? JsonArray ?: throw IllegalArgumentException("commands must be an array")
        if (commands.isEmpty()) throw IllegalArgumentException("commands must not be empty")
        if (commands.size > limits.commandsPerBatch) {
            throw BridgeException(
                BridgeErrors.PAYLOAD_TOO_LARGE,
                "At most ${limits.commandsPerBatch} commands per batch",
            )
        }
        val problems = mutableListOf<String>()
        commands.forEachIndexed { i, c ->
            val type = ((c as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            if (!RemoteCommandAllowlist.allows(type)) {
                problems += "commands[$i]: command type '${type ?: "?"}' is not allowed over the bridge"
            } else if (type == "add_caption" && tooLongCaption(c)) {
                problems += "commands[$i]: caption text longer than ${limits.captionChars} characters"
            }
        }
        if (problems.isNotEmpty()) {
            throw BridgeException(
                BridgeErrors.COMMAND_REJECTED,
                "Command rejected",
                buildJsonObject { put("problems", buildJsonArray { problems.forEach { add(JsonPrimitive(it)) } }) },
            )
        }
        return commands
    }

    private fun tooLongCaption(c: JsonObject): Boolean =
        (c["captions"] as? JsonArray).orEmpty().any {
            (((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.length ?: 0) > limits.captionChars
        }

    /** Sends a notification to every authenticated session. */
    fun broadcast(
        method: String,
        params: JsonObject,
    ) {
        for (l in live.values) runCatching { l.writer.writeLine(notification(method, params)) }
    }

    /** Closes the sessions of one client (revocation), or all sessions when [clientId] is null. */
    fun disconnect(
        clientId: String?,
        reason: CloseReason,
    ) {
        for ((sid, l) in live.entries.toList()) {
            if (clientId != null && l.session.client.clientId != clientId) continue
            runCatching {
                l.writer.writeLine(
                    notification("session.closing", buildJsonObject { put("reason", reason.wire) }),
                )
            }
            runCatching { l.conn.close() }
            live.remove(sid)
        }
        if (clientId == null) {
            pendingConns.toList().forEach { runCatching { it.close() } }
            pendingConns.clear()
        }
        publish()
    }

    private fun publish() {
        _sessions.update { live.values.map { it.session }.sortedBy { it.sessionId } }
    }

    private fun parseObject(line: String): JsonObject? =
        try {
            val e = json.parseToJsonElement(line) as? JsonObject
            if (e != null && (e["jsonrpc"] as? JsonPrimitive)?.contentOrNull == "2.0") e else null
        } catch (e: SerializationException) {
            null
        }

    companion object {
        const val PROTOCOL = 1
        const val PARSE_ERROR = -32700
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        fun scopesJson(scopes: Set<BridgeScope>): JsonArray =
            buildJsonArray { BridgeScope.entries.filter { it in scopes }.forEach { add(JsonPrimitive(it.wire)) } }

        internal fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun notification(
            method: String,
            params: JsonObject,
        ): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString()

        fun response(
            id: JsonElement,
            result: JsonObject,
        ): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString()

        fun error(
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
                        val name = if (code >= 1000) BridgeErrors.name(code) else null
                        if (data != null || name != null) {
                            put(
                                "data",
                                JsonObject(
                                    (data ?: JsonObject(emptyMap())) +
                                        listOfNotNull(name?.let { "name" to JsonPrimitive(it) }),
                                ),
                            )
                        }
                    },
                )
            }.toString()
    }
}
