package dev.videoeditor.app.mcp

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import dev.videoeditor.core.mcp.bridge.BridgeConnection
import dev.videoeditor.core.mcp.bridge.BridgeEndpoint
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The bridge listener (docs/design/security.md B2.1): an abstract-namespace Unix socket, never an IP socket.
 * Peers other than adbd (shell, uid 2000) and this app are closed before they can send a byte.
 * Device-only code: not covered by JVM tests.
 */
class LocalSocketBridgeListener(
    private val name: String = BridgeEndpoint.SOCKET_NAME,
    private val allowedUids: Set<Int> = setOf(SHELL_UID, Process.myUid()),
) : BridgeListener {
    @Volatile private var server: LocalServerSocket? = null

    override fun start(onConnection: (BridgeConnection) -> Unit) {
        if (server != null) return
        val s = LocalServerSocket(name) // abstract namespace
        server = s
        Thread({
            while (server === s) {
                val sock =
                    try {
                        s.accept()
                    } catch (e: IOException) {
                        break
                    }
                if (server !== s) {
                    runCatching { sock.close() }
                    break
                }
                val uid = runCatching { sock.peerCredentials.uid }.getOrDefault(-1)
                if (uid !in allowedUids) {
                    runCatching { sock.close() }
                    continue
                }
                onConnection(LocalSocketConnection(sock))
            }
        }, "vedit-bridge-accept").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        val s = server ?: return
        server = null
        // close() does not reliably interrupt accept() on every Android version: poke it with one connection.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
        runCatching { s.close() }
    }

    private class LocalSocketConnection(
        private val socket: LocalSocket,
    ) : BridgeConnection {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream

        override fun setReadTimeoutMs(ms: Int) {
            socket.soTimeout = ms
        }

        override fun close() = socket.close()
    }

    companion object {
        const val SHELL_UID = 2000
    }
}
