package dev.videoeditor.core.mcp

import dev.videoeditor.core.mcp.bridge.BridgeScope
import dev.videoeditor.core.mcp.bridge.FakePhone
import dev.videoeditor.timeline.json.ProjectLoadResult
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.sample.SampleProjects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class McpServerTest {
    @TempDir
    lateinit var dir: Path

    private fun sampleFile(name: String = "p.json"): Path =
        dir.resolve(name).also { Files.writeString(it, TimelineJson.encodeProject(SampleProjects.twoClipDemo())) }

    /** Runs the server over in-memory streams and returns the reply lines as JSON. */
    private fun converse(
        backend: ProjectBackend,
        vararg lines: String,
        maxBytes: Int = 4 shl 20,
    ): List<JsonObject> {
        val out = ByteArrayOutputStream()
        McpServer(
            backend,
            maxMessageBytes = maxBytes,
        ).run(ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray()), out)
        return out
            .toString(
                Charsets.UTF_8,
            ).lines()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun call(
        id: Int,
        tool: String,
        args: String = "{}",
    ) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""

    private val init =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private fun JsonObject.result() = this["result"]!!.jsonObject

    private fun JsonObject.structured() = result()["structuredContent"]!!.jsonObject

    @Test
    fun handshakeToolsListAndCall() {
        val replies =
            converse(
                FileProjectBackend(sampleFile()),
                init,
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                call(3, "list_clips"),
                """{"jsonrpc":"2.0","id":4,"method":"ping"}""",
            )
        assertEquals(4, replies.size) // the notification gets no reply
        val initResult = replies[0].result()
        assertEquals("2025-11-25", initResult["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(initResult["capabilities"]!!.jsonObject.containsKey("tools"))
        val tools = replies[1].result()["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "get_project",
                "list_clips",
                "split",
                "trim",
                "move",
                "delete",
                "add_caption",
                "apply_cuts",
                "export",
                "get_transcript",
            ),
            tools.map { it["name"]!!.jsonPrimitive.content },
        )
        for (t in tools) assertEquals("object", t["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val delete = tools.first { it["name"]!!.jsonPrimitive.content == "delete" }
        assertTrue(delete["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.boolean)
        assertTrue(tools[0]["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        val clips = replies[2].structured()["clips"]!!.jsonArray
        assertEquals(3, clips.size)
        assertFalse(replies[2].result()["isError"]!!.jsonPrimitive.boolean)
        assertEquals(JsonObject(emptyMap()), replies[3].result())
    }

    @Test
    fun protocolVersionNegotiation() {
        val b = FileProjectBackend(sampleFile())

        fun negotiated(v: String) =
            converse(
                b,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$v","capabilities":{}}}""",
            )[0]
                .result()["protocolVersion"]!!
                .jsonPrimitive.content
        assertEquals("2025-11-25", negotiated("2025-11-25"))
        assertEquals("2025-06-18", negotiated("2025-06-18"))
        assertEquals("2026-07-28", negotiated("2026-07-28"))
        assertEquals("2025-11-25", negotiated("2099-01-01")) // unknown: offer the latest legacy version
        // Stateless 2026-07-28 style: discover, then call with the version in _meta and no handshake.
        val r =
            converse(
                b,
                """{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{}}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"1999-01-01"}}}""",
            )
        assertTrue(
            r[0]
                .result()["supportedVersions"]!!
                .jsonArray
                .map {
                    it.jsonPrimitive.content
                }.containsAll(listOf("2026-07-28", "2025-11-25")),
        )
        assertEquals(10, r[1].result()["tools"]!!.jsonArray.size)
        assertEquals(-32602, r[2]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun protocolErrors() {
        val r =
            converse(
                FileProjectBackend(sampleFile()),
                "not json",
                """{"jsonrpc":"2.0","id":2,"method":"nope"}""",
                // import_media is registered in ToolRegistry.all, but not exposed over MCP.
                call(3, "import_media", """{"uri":"file:///etc/passwd"}"""),
                """[{"jsonrpc":"2.0","id":4,"method":"ping"}]""",
            )
        assertEquals(-32700, r[0]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, r[1]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32602, r[2]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32600, r[3]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun oversizedMessageIsRejectedAndServerContinues() {
        val big = """{"jsonrpc":"2.0","id":9,"method":"ping","params":{"pad":"${"x".repeat(5000)}"}}"""
        val r =
            converse(
                FileProjectBackend(sampleFile()),
                big,
                """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
                maxBytes = 1024,
            )
        assertEquals(2, r.size)
        assertEquals(-32600, r[0]["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(2, r[1]["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun offlineSplitThenGetProjectReflectsIt() {
        val f = sampleFile()
        val r =
            converse(
                FileProjectBackend(f),
                init,
                call(2, "split", """{"clipIds":["clip_4"],"atUs":2000000}"""),
                call(3, "get_project", """{"full":true}"""),
                call(4, "split", """{"clipIds":["clip_4"],"atUs":99000000}"""),
                call(5, "export"),
                """{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"vedit://project/demo"}}""",
            )
        val split = r[1].structured()
        assertTrue(split["ok"]!!.jsonPrimitive.boolean)
        assertEquals("clip_7", split["createdIds"]!!.jsonArray[0].jsonPrimitive.content)
        val clips =
            r[2]
                .structured()["tracks"]!!
                .jsonArray[0]
                .jsonObject["clips"]!!
                .jsonArray
        assertEquals(3, clips.size)
        // The file on disk has the edit too.
        val onDisk = (TimelineJson.decodeProject(Files.readString(f)) as ProjectLoadResult.Success).project
        assertEquals(3, onDisk.tracks[0].clips.size)
        // A failing command is an error result and changes nothing.
        assertTrue(r[3].result()["isError"]!!.jsonPrimitive.boolean)
        // Export is unsupported offline, with a clear message.
        val ex = r[4].structured()["error"]!!.jsonObject
        assertEquals("UNSUPPORTED", ex["type"]!!.jsonPrimitive.content)
        assertTrue(ex["message"]!!.jsonPrimitive.content.contains("offline"))
        val text =
            r[5]
                .result()["contents"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content
        assertTrue(text.contains("clip_7"))
    }

    @Test
    fun pathOutsideWorkspaceAndSymlinkEscapesAreRefused() {
        val ws = Files.createDirectory(dir.resolve("ws"))
        val inside = ws.resolve("ok.json")
        Files.writeString(inside, TimelineJson.encodeProject(SampleProjects.twoClipDemo()))
        val outside = sampleFile("outside.json")
        assertEquals(inside.toRealPath(), PathGuard.resolveProject("ok.json", ws.toString()))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { PathGuard.resolveProject("../outside.json", ws.toString()) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { PathGuard.resolveProject(outside.toString(), ws.toString()) }
        val link = ws.resolve("link.json")
        Files.createSymbolicLink(link, outside)
        assertThrows(IllegalArgumentException::class.java) { PathGuard.resolveProject("link.json", ws.toString()) }
        assertThrows(IllegalArgumentException::class.java) { PathGuard.resolveProject(link.toString(), null) }
        val sub = Files.createDirectory(ws.resolve("sub"))
        Files.writeString(sub.resolve("deep.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { PathGuard.resolveProject("sub/deep.json", ws.toString()) }
    }

    @Test
    fun bridgeBackendDrivesThePhone() {
        val phone = FakePhone()
        try {
            val creds = phone.pairClient(BridgeScope.entries.toSet())
            val backend = BridgeProjectBackend(phone.endpoint, { creds }, exportPollMs = 0)
            val r =
                converse(
                    backend,
                    init,
                    call(2, "list_clips"),
                    call(3, "split", """{"clipIds":["clip_4"],"atUs":2000000}"""),
                    call(4, "get_project", """{"full":true}"""),
                    call(5, "export"),
                    call(6, "get_transcript"),
                )
            assertEquals(3, r[1].structured()["clips"]!!.jsonArray.size)
            assertTrue(r[2].structured()["ok"]!!.jsonPrimitive.boolean, r[2].toString())
            assertEquals(
                3,
                phone.session.project.tracks[0]
                    .clips.size,
            )
            assertEquals(
                3,
                r[3]
                    .structured()["tracks"]!!
                    .jsonArray[0]
                    .jsonObject["clips"]!!
                    .jsonArray.size,
            )
            val export = r[4].structured()
            assertEquals("awaiting_confirmation", export["state"]!!.jsonPrimitive.content)
            assertTrue(export["note"]!!.jsonPrimitive.content.contains("confirm"))
            assertTrue(r[5].result()["isError"]!!.jsonPrimitive.boolean) // no transcription on the fake phone

            // The user edits on the phone: the next MCP edit is refused as stale and nothing is applied.
            phone.session.undo()
            val before = phone.session.project
            val stale = converse(backend, call(7, "split", """{"clipIds":["clip_5"],"atUs":6000000}"""))[0].structured()
            assertEquals("STALE_REVISION", stale["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(before, phone.session.project)
        } finally {
            phone.close()
        }
    }

    @Test
    fun bridgeBackendWithoutCredentialsExplainsPairing() {
        val phone = FakePhone()
        try {
            val r = converse(BridgeProjectBackend(phone.endpoint, { null }), call(1, "list_clips"))[0].structured()
            assertTrue(
                r["error"]!!
                    .jsonObject["message"]!!
                    .jsonPrimitive.content
                    .contains("vedit-mcp pair"),
            )
        } finally {
            phone.close()
        }
    }

    @Test
    fun credentialsFileIsPrivateAndWiderPermissionsAreRefused() {
        val phone = FakePhone()
        try {
            val creds = phone.pairClient()
            val path = dir.resolve("cfg/vedit-mcp/credentials.json")
            CredentialsFile.write(path, creds)
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(path)),
            )
            assertEquals(
                "rwx------",
                java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(path.parent)),
            )
            val back = CredentialsFile.read(path)!!
            assertEquals(creds.clientId, back.clientId)
            assertEquals(creds.token, back.token)
            Files.setPosixFilePermissions(
                path,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-r--r--"),
            )
            val e = assertThrows(java.io.IOException::class.java) { CredentialsFile.read(path) }
            assertFalse(e.message!!.contains(creds.token.use { it }))
            assertEquals(
                Path.of("/x/c.json"),
                CredentialsFile.defaultPath(mapOf(CredentialsFile.ENV to "/x/c.json")),
            )
            assertEquals(
                Path.of("/cfg/vedit-mcp/credentials.json"),
                CredentialsFile.defaultPath(mapOf("XDG_CONFIG_HOME" to "/cfg")),
            )
        } finally {
            phone.close()
        }
    }

    @Test
    fun stdioProcessEndToEnd() {
        val launcher = System.getProperty("veditMcpLauncher") ?: return
        assertTrue(Files.isExecutable(Path.of(launcher)), "run :core:mcp:installDist first: $launcher")
        val f = sampleFile()
        val p =
            ProcessBuilder(launcher, "stdio", "--project", f.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        p.outputStream.bufferedWriter().use { w ->
            for (l in listOf(
                init,
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                call(2, "split", """{"clipIds":["clip_5"],"atUs":7000000}"""),
                call(3, "list_clips"),
            )) {
                w.write(l)
                w.newLine()
            }
        }
        val lines = p.inputStream.bufferedReader().readLines()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        assertEquals(3, lines.size, lines.joinToString("\n")) // stdout carries protocol messages only
        val replies = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(replies[1].structured()["ok"]!!.jsonPrimitive.boolean)
        assertEquals(4, replies[2].structured()["clips"]!!.jsonArray.size)
    }
}
