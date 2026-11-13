package dev.videoeditor.core.mcp.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.security.SecureRandom

data class BridgeNotification(
    val method: String,
    val params: JsonObject,
)

/** Opens a raw connection to an endpoint. Replaceable in tests. */
fun interface BridgeConnector {
    fun open(endpoint: BridgeEndpoint): BridgeConnection
}

object DefaultBridgeConnector : BridgeConnector {
    override fun open(endpoint: BridgeEndpoint): BridgeConnection =
        when (endpoint) {
            is BridgeEndpoint.Tcp -> {
                val s = Socket()
                s.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
                s.tcpNoDelay = true
                JavaSocketConnection(s)
            }
            is BridgeEndpoint.UnixSocket -> {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(UnixDomainSocketAddress.of(endpoint.path))
                object : BridgeConnection {
                    override val input: InputStream = Channels.newInputStream(ch)
                    override val output: OutputStream = Channels.newOutputStream(ch)

                    override fun setReadTimeoutMs(ms: Int) = Unit

                    override fun close() = ch.close()
                }
            }
        }
}

/**
 * vedit-mcp's side of the bridge (B5, B9). Synchronous request/response over one connection; notifications
 * that arrive between responses are passed to [onNotification]. Never logs or includes the token in errors.
 */
class BridgeClient(
    private val connector: BridgeConnector = DefaultBridgeConnector,
    private val clientVersion: String = "0.1.0",
    private val random: SecureRandom = SecureRandom(),
    private val onNotification: (BridgeNotification) -> Unit = {},
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private var conn: BridgeConnection? = null
    private var reader: LineReader? = null
    private var writer: LineWriter? = null
    private var nextId = 1L

    var grantedScopes: Set<BridgeScope> = emptySet()
        private set

    val isConnected: Boolean get() = conn != null

    /** One-time pairing. Returns credentials for the caller to persist with mode 0600. */
    suspend fun pair(
        endpoint: BridgeEndpoint,
        code: String,
        clientName: String,
    ): BridgeCredentials =
        withContext(Dispatchers.IO) {
            close()
            val c = connector.open(endpoint)
            try {
                val r = LineReader(c.input)
                val w = LineWriter(c.output)
                val challenge = readChallenge(r)
                if (!challenge.pairingOpen) {
                    throw BridgeException(
                        BridgeErrors.UNAUTHENTICATED,
                        "Pairing is not open on the phone: open 'Pair a new client' first",
                    )
                }
                val nonce = BridgeCrypto.b64url(BridgeCrypto.randomBytes(16, random))
                w.writeLine(
                    request(
                        1,
                        "bridge.pair",
                        buildJsonObject {
                            put("protocol", BridgeProtocolServer.PROTOCOL)
                            put("code", code)
                            put("clientName", clientName)
                            put("nonce", nonce)
                        },
                    ),
                )
                val result = readResponse(r, 1L)
                val token = SecretToken.of(result.string("token"))
                val bridgeId = result.string("bridgeId")
                if (bridgeId !=
                    challenge.bridgeId
                ) {
                    throw BridgeException(BridgeErrors.UNAUTHENTICATED, "Bridge id mismatch")
                }
                BridgeCredentials(bridgeId, result.string("clientId"), token, scopes(result))
            } finally {
                runCatching { c.close() }
            }
        }

    /** Connects, verifies bridgeId and serverProof, authenticates. Throws [BridgeException] on failure. */
    suspend fun connect(
        endpoint: BridgeEndpoint,
        credentials: BridgeCredentials,
    ): Unit =
        withContext(Dispatchers.IO) {
            close()
            val c = connector.open(endpoint)
            try {
                val r = LineReader(c.input)
                val w = LineWriter(c.output)
                val challenge = readChallenge(r)
                if (!BridgeCrypto.constantTimeEquals(challenge.bridgeId, credentials.bridgeId)) {
                    throw BridgeException(
                        BridgeErrors.UNAUTHENTICATED,
                        "The endpoint is not the phone this client was paired with (bridge id differs). Pair again.",
                    )
                }
                val nonceC = BridgeCrypto.randomBytes(BridgeCrypto.NONCE_BYTES, random)
                val verifier = BridgeCrypto.verifier(credentials.token)
                val proof =
                    BridgeCrypto.clientProof(
                        verifier,
                        credentials.bridgeId,
                        credentials.clientId,
                        challenge.nonceS,
                        nonceC,
                    )
                w.writeLine(
                    request(
                        1,
                        "bridge.auth",
                        buildJsonObject {
                            put("protocol", BridgeProtocolServer.PROTOCOL)
                            put("clientId", credentials.clientId)
                            put("nonceC", BridgeCrypto.b64url(nonceC))
                            put("proof", BridgeCrypto.b64url(proof))
                            put(
                                "client",
                                buildJsonObject {
                                    put("name", "vedit-mcp")
                                    put("version", clientVersion)
                                },
                            )
                        },
                    ),
                )
                val result = readResponse(r, 1L)
                val serverProof = BridgeCrypto.unb64url(result.string("serverProof")) ?: ByteArray(0)
                val expected =
                    BridgeCrypto.serverProof(
                        verifier,
                        credentials.bridgeId,
                        credentials.clientId,
                        challenge.nonceS,
                        nonceC,
                    )
                if (!BridgeCrypto.constantTimeEquals(expected, serverProof)) {
                    throw BridgeException(
                        BridgeErrors.UNAUTHENTICATED,
                        "The phone failed to prove it knows this client's token",
                    )
                }
                grantedScopes = scopes(result)
                conn = c
                reader = r
                writer = w
                nextId = 2
            } catch (e: Exception) {
                runCatching { c.close() }
                throw e
            }
        }

    /** Sends one request and waits for its response. */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject =
        withContext(Dispatchers.IO) {
            synchronized(this@BridgeClient) {
                val w = writer ?: throw BridgeException(BridgeErrors.SESSION_CLOSED, "Not connected to the phone")
                val r = reader!!
                val id = nextId++
                try {
                    w.writeLine(request(id, method, params))
                    readResponse(r, id)
                } catch (e: IOException) {
                    close()
                    throw BridgeException(BridgeErrors.SESSION_CLOSED, "Connection to the phone was lost")
                }
            }
        }

    suspend fun getProject(projectId: String? = null): JsonObject =
        call("project.get", buildJsonObject { projectId?.let { put("projectId", it) } })

    suspend fun apply(
        projectId: String?,
        baseRevision: Long,
        requestId: String,
        label: String,
        commands: JsonArray,
    ): JsonObject =
        call(
            "command.apply",
            buildJsonObject {
                projectId?.let { put("projectId", it) }
                put("baseRevision", baseRevision)
                put("requestId", requestId)
                put("label", label)
                put("commands", commands)
            },
        )

    override fun close() {
        runCatching { conn?.close() }
        conn = null
        reader = null
        writer = null
    }

    private class Challenge(
        val bridgeId: String,
        val nonceS: ByteArray,
        val pairingOpen: Boolean,
    )

    private fun readChallenge(r: LineReader): Challenge {
        val line =
            r.readLine(4096) ?: throw BridgeException(BridgeErrors.SESSION_CLOSED, "The phone closed the connection")
        val o = parse(line)
        if ((o["method"] as? JsonPrimitive)?.contentOrNull != "bridge.challenge") {
            throw BridgeException(BridgeErrors.UNAUTHENTICATED, "The endpoint did not speak the bridge protocol")
        }
        val p = o["params"]?.jsonObject ?: JsonObject(emptyMap())
        val protocol = (p["protocol"] as? JsonPrimitive)?.intOrNull
        if (protocol != BridgeProtocolServer.PROTOCOL) {
            throw BridgeException(
                BridgeErrors.UNSUPPORTED,
                "Unsupported bridge protocol $protocol; update the app or vedit-mcp",
            )
        }
        val nonceS =
            BridgeCrypto.unb64url(p.string("nonceS"))?.takeIf { it.size == BridgeCrypto.NONCE_BYTES }
                ?: throw BridgeException(BridgeErrors.UNAUTHENTICATED, "Bad challenge")
        return Challenge(p.string("bridgeId"), nonceS, (p["pairingOpen"] as? JsonPrimitive)?.contentOrNull == "true")
    }

    private fun readResponse(
        r: LineReader,
        id: Long,
    ): JsonObject {
        while (true) {
            val line =
                r.readLine(RESPONSE_LIMIT)
                    ?: throw BridgeException(BridgeErrors.SESSION_CLOSED, "The phone closed the connection")
            val o = parse(line)
            val rid = o["id"]
            if (rid == null) {
                val method = (o["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
                if (method == "session.closing") {
                    close()
                    throw BridgeException(
                        BridgeErrors.SESSION_CLOSED,
                        "The phone closed the session (${(params["reason"] as? JsonPrimitive)?.contentOrNull ?: "unknown"})",
                    )
                }
                onNotification(BridgeNotification(method, params))
                continue
            }
            if ((rid as? JsonPrimitive)?.contentOrNull != id.toString()) {
                (o["error"] as? JsonObject)?.let { throw toException(it) }
                continue
            }
            (o["error"] as? JsonObject)?.let { throw toException(it) }
            return o["result"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    private fun toException(e: JsonObject): BridgeException {
        val code = (e["code"] as? JsonPrimitive)?.intOrNull ?: -1
        val msg = (e["message"] as? JsonPrimitive)?.contentOrNull ?: "error"
        return BridgeException(code, msg, e["data"] as? JsonObject)
    }

    private fun parse(line: String): JsonObject =
        try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: SerializationException) {
            throw BridgeException(BridgeErrors.UNAUTHENTICATED, "The endpoint did not speak the bridge protocol")
        } catch (e: IllegalArgumentException) {
            throw BridgeException(BridgeErrors.UNAUTHENTICATED, "The endpoint did not speak the bridge protocol")
        }

    private fun scopes(o: JsonObject): Set<BridgeScope> =
        (o["scopes"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(BridgeScope::fromWire) }
            .toSet()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?: throw BridgeException(BridgeErrors.UNAUTHENTICATED, "Malformed reply from the phone ($key missing)")

    companion object {
        private const val RESPONSE_LIMIT = (8 shl 20) + 4096

        private fun request(
            id: Long,
            method: String,
            params: JsonObject,
        ): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
    }
}
