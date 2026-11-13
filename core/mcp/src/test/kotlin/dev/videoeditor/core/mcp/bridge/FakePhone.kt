package dev.videoeditor.core.mcp.bridge

import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** A fake phone: the real BridgeProtocolServer over loopback TCP (test-only listener). */
class FakePhone(
    val clock: AtomicLong = AtomicLong(1_000_000L),
    pairingLimits: PairingLimits = PairingLimits(wrongCodeDelayMs = 0, approvalTimeoutMs = 3_000),
    authLimits: AuthLimits = AuthLimits(),
    bridgeLimits: BridgeLimits = BridgeLimits(),
    var readOnlyReason: String? = null,
) : AutoCloseable {
    val random = SecureRandom()
    val store = InMemoryClientStore(BridgeCrypto.newBridgeId(random))
    val session = EditorSession(SampleProjects.twoClipDemo())
    val exportGate = ExportGate()
    val server: BridgeProtocolServer by lazy {
        BridgeProtocolServer(store, verifier, pairing, sink, clock = { clock.get() }, limits = bridgeLimits)
    }
    val pairing =
        DefaultPairingManager(store, clock = { clock.get() }, random = random, limits = pairingLimits, onRevoked = {
            server.disconnect(it, CloseReason.REVOKED)
        })
    val verifier = DefaultTokenVerifier(store, clock = { clock.get() }, random = random, limits = authLimits)
    val sink = SessionBridgeSink(session, { readOnlyReason }, exportGate)

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val endpoint = BridgeEndpoint.Tcp(port = socket.localPort)

    private val acceptor =
        Thread {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { runBlocking { server.serve(JavaSocketConnection(s)) } }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

    /** Opens a pairing offer and answers the approval prompt automatically (Allow with [scopes], or Deny). */
    fun pairAutomatically(
        allow: Boolean = true,
        scopes: Set<BridgeScope> = BridgeScope.DEFAULT,
    ): String {
        val offer = runBlocking { pairing.beginPairing() }
        Thread {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val s = pairing.pairing.value
                if (s is PairingState.AwaitingApproval) {
                    pairing.answer(s.requestId, allow, scopes)
                    return@Thread
                }
                Thread.sleep(5)
            }
        }.apply { isDaemon = true }.start()
        return offer.code
    }

    fun pairClient(scopes: Set<BridgeScope> = BridgeScope.DEFAULT): BridgeCredentials {
        val code = pairAutomatically(true, scopes)
        return runBlocking { BridgeClient().pair(endpoint, code, "test@host") }
    }

    fun rawConnect(): Socket = Socket(InetAddress.getLoopbackAddress(), socket.localPort).apply { soTimeout = 5_000 }

    override fun close() {
        socket.close()
        server.disconnect(null, CloseReason.APP_STOPPED)
    }
}
