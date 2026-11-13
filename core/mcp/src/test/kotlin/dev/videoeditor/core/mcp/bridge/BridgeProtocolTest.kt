package dev.videoeditor.core.mcp.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

class BridgeProtocolTest {
    private val phone = FakePhone()

    @AfterEach
    fun tearDown() = phone.close()

    private fun connected(creds: BridgeCredentials): BridgeClient =
        BridgeClient().also {
            runBlocking { it.connect(phone.endpoint, creds) }
        }

    private fun split(
        clientId: String = "clip_4",
        atUs: Long = 2_000_000,
    ) = buildJsonObject {
        put("type", "split_clip")
        put("clipIds", JsonArray(listOf(JsonPrimitive(clientId))))
        put("atUs", atUs)
    }

    private fun apply(
        c: BridgeClient,
        base: Long,
        requestId: String = UUID.randomUUID().toString(),
        cmd: JsonObject = split(),
    ) = runBlocking { c.apply(null, base, requestId, "split", JsonArray(listOf(cmd))) }

    @Test
    fun pairThenAuthenticateAndEdit() {
        val creds = phone.pairClient()
        val c = connected(creds)
        val p = runBlocking { c.getProject() }
        val rev = p["revision"]!!.jsonPrimitive.long
        assertTrue(p.toString().contains("redacted:asset_1"))
        assertFalse(p.toString().contains("asset:///demo.mp4")) // no URIs leave the phone
        val r = apply(c, rev)
        assertEquals(rev + 1, r["revision"]!!.jsonPrimitive.long)
        assertEquals(
            3,
            phone.session.project.tracks[0]
                .clips.size,
        )
        val entry = phone.session.undoHistory.last()
        assertEquals("Claude (MCP): split", entry.label)
        assertEquals(dev.videoeditor.timeline.session.Origin.MCP, entry.origin)
        assertEquals(1, phone.server.sessions.value.size)
    }

    @Test
    fun wrongTokenFails() {
        val creds = phone.pairClient()
        val bad =
            BridgeCredentials(creds.bridgeId, creds.clientId, SecretToken.of("vedb1_" + "A".repeat(43)), creds.scopes)
        val e = assertThrows(BridgeException::class.java) { connected(bad) }
        assertEquals(BridgeErrors.UNAUTHENTICATED, e.code)
    }

    @Test
    fun unknownAndKnownClientIdFailuresAreIndistinguishable() {
        val creds = phone.pairClient()
        val wrongToken =
            BridgeCredentials(creds.bridgeId, creds.clientId, SecretToken.of("vedb1_" + "B".repeat(43)), creds.scopes)
        val unknown = BridgeCredentials(creds.bridgeId, "c_unknownunknownunknown", creds.token, creds.scopes)
        val e1 = assertThrows(BridgeException::class.java) { connected(wrongToken) }
        val e2 = assertThrows(BridgeException::class.java) { connected(unknown) }
        assertEquals(e1.code, e2.code)
        assertEquals(e1.message, e2.message)
    }

    @Test
    fun replayedAuthOnNewConnectionFails() {
        val creds = phone.pairClient()
        // Capture a valid bridge.auth from connection 1 by computing it exactly as the client would.
        val s1 = phone.rawConnect()
        val r1 = s1.getInputStream().bufferedReader()
        val ch1 = Json.parseToJsonElement(r1.readLine()).jsonObject["params"]!!.jsonObject
        val nonceS1 = BridgeCrypto.unb64url(ch1["nonceS"]!!.jsonPrimitive.content)!!
        val nonceC = ByteArray(32) { 7 }
        val proof =
            BridgeCrypto.clientProof(
                BridgeCrypto.verifier(creds.token),
                creds.bridgeId,
                creds.clientId,
                nonceS1,
                nonceC,
            )
        val auth =
            """{"jsonrpc":"2.0","id":1,"method":"bridge.auth",""" +
                """"params":{"protocol":1,"clientId":"${creds.clientId}",""" +
                """"nonceC":"${BridgeCrypto.b64url(nonceC)}","proof":"${BridgeCrypto.b64url(proof)}"}}"""
        s1.getOutputStream().write((auth + "\n").toByteArray())
        assertTrue(r1.readLine().contains("serverProof")) // original is accepted
        s1.close()
        // Replay on a new connection: different nonceS, so it fails.
        val s2 = phone.rawConnect()
        val r2 = s2.getInputStream().bufferedReader()
        r2.readLine()
        s2.getOutputStream().write((auth + "\n").toByteArray())
        val reply = r2.readLine()
        assertTrue(reply.contains("\"code\":1001"), reply)
        assertNull(r2.readLine()) // and the socket is closed
    }

    @Test
    fun httpRequestLineClosesWithNoResponseBytes() {
        val s = phone.rawConnect()
        val input = s.getInputStream()
        val challenge = input.bufferedReader().readLine()
        assertTrue(challenge.contains("bridge.challenge"))
        s.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        assertEquals(-1, input.read()) // closed, nothing after the challenge
    }

    @Test
    fun oversizedFirstMessageIsDropped() {
        val s = phone.rawConnect()
        val reader = s.getInputStream()
        // read challenge line byte-wise
        while (reader.read() != '\n'.code) {
            // skip the challenge
        }
        s.getOutputStream().write(
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bridge.auth\",\"params\":{\"x\":\"" +
                    "a".repeat(5000) +
                    "\"}}\n"
            ).toByteArray(),
        )
        assertEquals(-1, reader.read())
    }

    @Test
    fun oversizedRequestAfterAuthIsRejected() {
        phone.close()
        val small = FakePhone(bridgeLimits = BridgeLimits(requestBytes = 2048))
        try {
            val creds = small.pairClient()
            val c = BridgeClient().also { runBlocking { it.connect(small.endpoint, creds) } }
            val e =
                assertThrows(BridgeException::class.java) {
                    runBlocking { c.call("project.get", buildJsonObject { put("pad", "x".repeat(4096)) }) }
                }
            assertEquals(BridgeErrors.PAYLOAD_TOO_LARGE, e.code)
        } finally {
            small.close()
        }
    }

    @Test
    fun nonAllowlistedCommandTypeIsRejected() {
        val c = connected(phone.pairClient())
        val rev = runBlocking { c.getProject() }["revision"]!!.jsonPrimitive.long
        for (type in listOf("import_media", "set_project_settings", "batch", "remove_track", "add_clip")) {
            val e =
                assertThrows(BridgeException::class.java) {
                    apply(c, rev, cmd = buildJsonObject { put("type", type) })
                }
            assertEquals(BridgeErrors.COMMAND_REJECTED, e.code, type)
        }
        assertEquals(rev, phone.session.revision)
    }

    @Test
    fun editWithoutScopeIsRejected() {
        val c = connected(phone.pairClient(setOf(BridgeScope.READ)))
        val rev = runBlocking { c.getProject() }["revision"]!!.jsonPrimitive.long
        val e = assertThrows(BridgeException::class.java) { apply(c, rev) }
        assertEquals(BridgeErrors.FORBIDDEN_SCOPE, e.code)
        val e2 =
            assertThrows(BridgeException::class.java) {
                runBlocking { c.call("export.request", buildJsonObject {}) }
            }
        assertEquals(BridgeErrors.FORBIDDEN_SCOPE, e2.code)
    }

    @Test
    fun staleBaseRevisionAppliesNothing() {
        val c = connected(phone.pairClient())
        val rev = runBlocking { c.getProject() }["revision"]!!.jsonPrimitive.long
        val before = phone.session.project
        val e = assertThrows(BridgeException::class.java) { apply(c, rev - 1) }
        assertEquals(BridgeErrors.STALE_REVISION, e.code)
        assertEquals(before, phone.session.project)
    }

    @Test
    fun repeatedRequestIdAppliesOnce() {
        val c = connected(phone.pairClient())
        val rev = runBlocking { c.getProject() }["revision"]!!.jsonPrimitive.long
        val id = UUID.randomUUID().toString()
        val a = apply(c, rev, id)
        val b = apply(c, rev, id) // retry with the old base revision: returns the original result
        assertEquals(a, b)
        assertEquals(1, phone.session.undoHistory.size)
    }

    @Test
    fun editsAreRejectedWhileAiSuggestionIsPending() {
        val c = connected(phone.pairClient())
        val rev = runBlocking { c.getProject() }["revision"]!!.jsonPrimitive.long
        phone.readOnlyReason = "An AI suggestion is pending on the phone"
        val e = assertThrows(BridgeException::class.java) { apply(c, rev) }
        assertEquals(BridgeErrors.COMMAND_REJECTED, e.code)
        assertTrue(e.message!!.contains("AI suggestion"))
        assertEquals(rev, phone.session.revision)
    }

    @Test
    fun exportNeverStartsWithoutConfirmation() {
        val c = connected(phone.pairClient(BridgeScope.entries.toSet()))
        val r = runBlocking { c.call("export.request", buildJsonObject { put("request", buildJsonObject {}) }) }
        assertEquals("awaiting_confirmation", r["state"]!!.jsonPrimitive.content)
        val id = r["exportId"]!!.jsonPrimitive.content
        assertEquals(
            id,
            phone.exportGate.prompt.value!!
                .exportId,
        )
        assertEquals(
            "test@host",
            phone.exportGate.prompt.value!!
                .clientName,
        )
        // A second request while one is pending is refused.
        assertThrows(BridgeException::class.java) {
            runBlocking { c.call("export.request", buildJsonObject {}) }
        }
        // Deny: nothing starts.
        assertNull(phone.exportGate.answer(id, allow = false))
        val s = runBlocking { c.call("export.status", buildJsonObject { put("exportId", id) }) }
        assertEquals("denied", s["state"]!!.jsonPrimitive.content)
        // Allow on a new request returns the prompt so the app starts the export.
        val id2 =
            runBlocking { c.call("export.request", buildJsonObject {}) }["exportId"]!!.jsonPrimitive.content
        assertEquals(id2, phone.exportGate.answer(id2, allow = true)!!.exportId)
        assertEquals(
            "running",
            phone.exportGate
                .status(id2)["state"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun revokedClientIsDisconnectedAndCannotReconnect() {
        val creds = phone.pairClient()
        val c = connected(creds)
        runBlocking { c.call("ping") }
        runBlocking { phone.pairing.revoke(creds.clientId) }
        val e = assertThrows(BridgeException::class.java) { runBlocking { c.call("ping") } }
        assertEquals(BridgeErrors.SESSION_CLOSED, e.code)
        val e2 = assertThrows(BridgeException::class.java) { connected(creds) }
        assertEquals(BridgeErrors.UNAUTHENTICATED, e2.code)
    }

    @Test
    fun killSwitchClosesSessions() {
        val c = connected(phone.pairClient())
        phone.server.disconnect(null, CloseReason.KILLED)
        val e = assertThrows(BridgeException::class.java) { runBlocking { c.call("ping") } }
        assertEquals(BridgeErrors.SESSION_CLOSED, e.code)
        assertTrue(
            phone.server.sessions.value
                .isEmpty(),
        )
    }

    @Test
    fun impostorPhoneIsDetected() {
        val creds = phone.pairClient()
        val other = FakePhone()
        try {
            // Same credentials against a different bridge: the client refuses before sending anything.
            val e =
                assertThrows(
                    BridgeException::class.java,
                ) { runBlocking { BridgeClient().connect(other.endpoint, creds) } }
            assertTrue(e.message!!.contains("bridge id"))
        } finally {
            other.close()
        }
    }

    @Test
    fun authRateLimitPerClient() {
        val creds = phone.pairClient()
        val bad =
            BridgeCredentials(creds.bridgeId, creds.clientId, SecretToken.of("vedb1_" + "C".repeat(43)), creds.scopes)
        repeat(5) { assertThrows(BridgeException::class.java) { connected(bad) } }
        // Now even the right token is refused for 60 s.
        val e = assertThrows(BridgeException::class.java) { connected(creds) }
        assertEquals(BridgeErrors.RATE_LIMITED, e.code)
        phone.clock.addAndGet(60_001)
        connected(creds)
    }

    @Test
    fun tokenNeverAppearsInLogsOrErrors() {
        val err = ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(PrintStream(err, true))
        val creds: BridgeCredentials
        val messages = mutableListOf<String>()
        try {
            creds = phone.pairClient()
            val c = connected(creds)
            runBlocking { c.getProject() }
            val bad =
                BridgeCredentials(
                    creds.bridgeId,
                    creds.clientId,
                    SecretToken.of("vedb1_" + "D".repeat(43)),
                    creds.scopes,
                )
            messages += assertThrows(BridgeException::class.java) { connected(bad) }.toString()
            messages += creds.toString()
            messages += creds.token.toString()
            messages +=
                phone.pairing.clients.value
                    .toString()
            messages +=
                phone.server.sessions.value
                    .toString()
        } finally {
            System.setErr(oldErr)
        }
        val token = creds.token.use { it }
        val secretPart = token.removePrefix("vedb1_")
        for (m in messages + err.toString()) {
            assertFalse(m.contains(secretPart), "token leaked into: $m")
        }
    }
}
