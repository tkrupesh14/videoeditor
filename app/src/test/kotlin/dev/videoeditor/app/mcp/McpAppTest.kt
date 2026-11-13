package dev.videoeditor.app.mcp

import dev.videoeditor.app.security.FakeCipher
import dev.videoeditor.app.security.FakePrefs
import dev.videoeditor.core.mcp.bridge.BridgeConnection
import dev.videoeditor.core.mcp.bridge.BridgeCrypto
import dev.videoeditor.core.mcp.bridge.BridgeScope
import dev.videoeditor.core.mcp.bridge.ExportGate
import dev.videoeditor.core.mcp.bridge.PairedClient
import dev.videoeditor.core.mcp.bridge.SessionBridgeSink
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import dev.videoeditor.ui.shell.McpStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class McpAppTest {
    private val random = SecureRandom()

    @Test
    fun clientStoreKeepsOnlyAnEncryptedVerifier() {
        val prefs = FakePrefs()
        val store = KeystoreClientStore(prefs, FakeCipher())
        val token = BridgeCrypto.newToken(random)
        val v = BridgeCrypto.verifier(token)
        store.add(PairedClient("c_1", "laptop", BridgeScope.DEFAULT, 1L, null), v)
        assertTrue(store.verifier("c_1")!!.contentEquals(v))
        assertEquals("laptop", store.list().single().name)
        val raw = prefs.values.values.joinToString()
        assertFalse(raw.contains(token.use { it }.removePrefix("vedb1_")))
        assertFalse(raw.contains(BridgeCrypto.b64url(v)))
        // bridgeId is stable across instances.
        assertEquals(store.bridgeId, KeystoreClientStore(prefs, FakeCipher()).bridgeId)
        store.touch("c_1", 99L)
        assertEquals(99L, store.list().single().lastUsedAtEpochMs)
        store.remove("c_1")
        assertNull(store.verifier("c_1"))
    }

    @Test
    fun lostKeystoreKeyDropsAllClients() {
        val cipher = FakeCipher()
        val store = KeystoreClientStore(FakePrefs(), cipher)
        store.add(PairedClient("c_1", "a", BridgeScope.DEFAULT, 1L, null), ByteArray(32) { 1 })
        store.add(PairedClient("c_2", "b", BridgeScope.DEFAULT, 1L, null), ByteArray(32) { 2 })
        cipher.deleteKey()
        assertNull(store.verifier("c_1"))
        assertTrue(store.list().isEmpty())
    }

    private class FakeListener : BridgeListener {
        var running = false
        var starts = 0

        override fun start(onConnection: (BridgeConnection) -> Unit) {
            running = true
            starts++
        }

        override fun stop() {
            running = false
        }
    }

    @Test
    fun bridgeIsOffByDefaultFollowsLifecycleAndStopIsAKillSwitch() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val listener = FakeListener()
            val gate = ExportGate()
            val session = EditorSession(SampleProjects.twoClipDemo())
            val c =
                McpBridgeController(
                    scope,
                    McpPorts({ KeystoreClientStore(FakePrefs(), FakeCipher()) }, { listener }),
                    SessionBridgeSink(session, { null }, gate),
                    gate,
                    Dispatchers.Unconfined,
                ) { _, _ -> }
            c.onStart()
            assertFalse(listener.running) // "Allow MCP control" is off at cold start
            assertEquals(McpStatus.Off, c.ui.value.status)
            c.setAllowed(true)
            assertTrue(listener.running)
            assertEquals(McpStatus.Waiting, c.ui.value.status)
            c.onStop()
            assertFalse(listener.running) // no listener while the activity is stopped
            c.onStart()
            assertTrue(listener.running)
            c.stop()
            assertFalse(listener.running)
            assertFalse(c.ui.value.allowed)
            c.onStart()
            assertFalse(listener.running) // stays off until the user switches it on again
        } finally {
            scope.cancel()
        }
    }
}
