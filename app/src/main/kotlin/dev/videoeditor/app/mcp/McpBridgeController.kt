package dev.videoeditor.app.mcp

import dev.videoeditor.core.mcp.bridge.BridgeCommandSink
import dev.videoeditor.core.mcp.bridge.BridgeConnection
import dev.videoeditor.core.mcp.bridge.BridgeEndpoint
import dev.videoeditor.core.mcp.bridge.BridgeProtocolServer
import dev.videoeditor.core.mcp.bridge.BridgeScope
import dev.videoeditor.core.mcp.bridge.ClientStore
import dev.videoeditor.core.mcp.bridge.CloseReason
import dev.videoeditor.core.mcp.bridge.DefaultPairingManager
import dev.videoeditor.core.mcp.bridge.DefaultTokenVerifier
import dev.videoeditor.core.mcp.bridge.ExportGate
import dev.videoeditor.core.mcp.bridge.PairingState
import dev.videoeditor.ui.shell.McpClientUi
import dev.videoeditor.ui.shell.McpExportPromptUi
import dev.videoeditor.ui.shell.McpPairingUi
import dev.videoeditor.ui.shell.McpStatus
import dev.videoeditor.ui.shell.McpUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Accepts bridge connections on the phone. The Android implementation is [LocalSocketBridgeListener]. */
interface BridgeListener {
    /** Starts accepting. Each accepted, peer-checked connection is handed to [onConnection]. Idempotent. */
    fun start(onConnection: (BridgeConnection) -> Unit)

    /** Stops accepting. Idempotent. */
    fun stop()
}

/** Platform pieces of the MCP bridge; fakes in JVM tests. */
class McpPorts(
    val clientStore: () -> ClientStore,
    val listener: () -> BridgeListener,
    val appVersion: String = "0.1.0",
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * Phone side of the MCP bridge (docs/design/security.md B2-B6): listens only while "Allow MCP control" is on
 * (off at every cold start) and the activity is started; [stop] is the kill switch. All protocol and auth
 * logic lives in :core:mcp; this class only wires it to the lifecycle and the UI.
 */
class McpBridgeController(
    private val scope: CoroutineScope,
    ports: McpPorts,
    sink: BridgeCommandSink,
    val exportGate: ExportGate,
    private val io: CoroutineDispatcher,
    onApplied: (clientName: String, label: String) -> Unit,
) {
    private val store: ClientStore = ports.clientStore()
    private val listenerFactory = ports.listener
    private var listener: BridgeListener? = null
    private val allowed = MutableStateFlow(false)
    private val listening = MutableStateFlow(false)
    private var started = false

    private lateinit var server: BridgeProtocolServer
    val pairing =
        DefaultPairingManager(store, clock = ports.clock, onRevoked = { id ->
            server.disconnect(id, CloseReason.REVOKED)
        })
    private val verifier = DefaultTokenVerifier(store, clock = ports.clock, onExpired = { pairing.refreshClients() })

    init {
        server =
            BridgeProtocolServer(
                store,
                verifier,
                pairing,
                sink,
                clock = ports.clock,
                appVersion = ports.appVersion,
                onApplied = { c, label ->
                    pairing.refreshClients()
                    onApplied(c.name, label)
                },
            )
    }

    val ui: StateFlow<McpUiState> =
        combine(
            allowed,
            listening,
            server.sessions,
            pairing.pairing,
            pairing.clients,
        ) { allow, on, sessions, p, clients ->
            Snapshot(allow, on, sessions.map { it.client.name }.distinct(), p, clients)
        }.combine(exportGate.prompt) { s, prompt ->
            McpUiState(
                available = true,
                allowed = s.allowed,
                status =
                    when {
                        s.names.isNotEmpty() -> McpStatus.Connected(s.names)
                        s.listening -> McpStatus.Waiting
                        else -> McpStatus.Off
                    },
                clients =
                    s.clients.map {
                        McpClientUi(
                            it.clientId,
                            it.name,
                            BridgeScope.entries.filter { sc -> sc in it.scopes }.map { sc -> sc.wire },
                            formatDate(it.createdAtEpochMs),
                            it.lastUsedAtEpochMs?.let(::formatDate),
                        )
                    },
                pairing =
                    when (val p = s.pairing) {
                        PairingState.Closed -> McpPairingUi.Closed
                        is PairingState.Offering ->
                            McpPairingUi.Offering(
                                p.offer.code,
                                p.offer.expiresAtEpochMs,
                                p.attemptsLeft,
                            )
                        is PairingState.AwaitingApproval -> McpPairingUi.AwaitingApproval(p.requestId, p.clientName)
                        is PairingState.LockedOut -> McpPairingUi.LockedOut(p.untilEpochMs)
                    },
                exportPrompt = prompt?.let { McpExportPromptUi(it.exportId, it.clientName, it.projectName) },
                socketName = BridgeEndpoint.SOCKET_NAME,
                port = BridgeEndpoint.DEFAULT_PORT,
            )
        }.stateIn(scope, SharingStarted.Eagerly, McpUiState(available = true))

    private data class Snapshot(
        val allowed: Boolean,
        val listening: Boolean,
        val names: List<String>,
        val pairing: PairingState,
        val clients: List<dev.videoeditor.core.mcp.bridge.PairedClient>,
    )

    /** The "Allow MCP control" switch. Turning it off is the same as Stop. */
    fun setAllowed(on: Boolean) {
        if (!on) {
            stop()
            return
        }
        allowed.value = true
        if (started) listen()
    }

    /** Activity onStart. */
    fun onStart() {
        started = true
        if (allowed.value) listen()
    }

    /** Activity onStop: close all sessions and the listener (no foreground service in v1). */
    fun onStop() {
        started = false
        shutDown(CloseReason.APP_STOPPED)
    }

    /** Kill switch: closes every session, stops the listener, turns "Allow MCP control" off. Tokens stay valid. */
    fun stop() {
        allowed.value = false
        shutDown(CloseReason.KILLED)
    }

    private fun shutDown(reason: CloseReason) {
        pairing.cancelPairing()
        exportGate.denyPending()
        listener?.stop()
        listener = null
        listening.value = false
        server.disconnect(null, reason)
    }

    private fun listen() {
        if (listener != null) return
        val l = listenerFactory()
        try {
            l.start { conn -> scope.launch(io) { server.serve(conn) } }
        } catch (e: Exception) {
            allowed.value = false
            throw e
        }
        listener = l
        listening.value = true
    }

    fun beginPairing() {
        scope.launch { pairing.beginPairing() }
    }

    fun cancelPairing() = pairing.cancelPairing()

    fun answerPairing(
        requestId: String,
        allow: Boolean,
        scopes: Set<String>,
    ) = pairing.answer(requestId, allow, scopes.mapNotNull(BridgeScope::fromWire).toSet())

    fun revoke(clientId: String) {
        scope.launch { pairing.revoke(clientId) }
    }

    fun revokeAll() {
        scope.launch { pairing.revokeAll() }
    }

    /** Sends a notification (e.g. project.changed) to every connected client. */
    fun broadcast(
        method: String,
        params: kotlinx.serialization.json.JsonObject,
    ) = server.broadcast(method, params)

    val hasSessions: Boolean get() = server.sessions.value.isNotEmpty()

    private fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
}
