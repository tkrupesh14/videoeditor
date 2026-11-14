package dev.videoeditor.core.ai.assistant

import dev.videoeditor.core.ai.assistant.MockAnthropic.Companion.error
import dev.videoeditor.core.ai.assistant.MockAnthropic.Companion.message
import dev.videoeditor.core.ai.assistant.MockAnthropic.Companion.text
import dev.videoeditor.core.ai.assistant.MockAnthropic.Companion.thinking
import dev.videoeditor.core.ai.assistant.MockAnthropic.Companion.toolUse
import dev.videoeditor.core.ai.key.Fixtures
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.timeline.command.ApplyCuts
import dev.videoeditor.timeline.command.SplitClip
import dev.videoeditor.timeline.model.TimeRange
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ClaudeEditAssistantTest {
    private val mock = MockAnthropic()
    private val project = SampleProjects.twoClipDemo(assetUri = "content://media/secret/holiday.mp4")

    @AfterEach
    fun tearDown() = mock.close()

    private fun session() = EditorSession(project)

    private fun JsonObject.messages(): JsonArray = this["messages"]!!.jsonArray

    private fun JsonElement.blocks(): List<JsonObject> = jsonObject["content"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.type(): String = this["type"]!!.jsonPrimitive.content

    @Test
    fun `tool_use split and apply_cuts then end_turn builds the expected preview`() =
        runBlocking {
            mock.enqueueJson(
                message(
                    listOf(
                        thinking(),
                        toolUse("tu_1", "split", """{"clipIds":["clip_4"],"atUs":2000000}"""),
                        toolUse("tu_2", "apply_cuts", """{"ranges":[{"startUs":1000000,"endUs":1500000}]}"""),
                    ),
                    "tool_use",
                ),
            )
            mock.enqueueJson(message(listOf(text("Split clip_4 at 2 s and cut 1.0-1.5 s.")), "end_turn"))
            val session = session()

            val result = mock.assistant().suggest(session, "cut half a second at 1s and split at 2s")

            val s = (result as AiResult.Suggested).suggestion
            val expected = session.newPreview()
            expected.submit(SplitClip(listOf("clip_4"), 2_000_000))
            expected.submit(ApplyCuts(listOf(TimeRange(1_000_000, 1_500_000))))
            assertEquals(expected.diff(), s.diff)
            assertEquals(expected.project, s.preview.project)
            assertEquals(project, session.project, "live session must be untouched")
            assertTrue(s.complete)
            assertEquals("Split clip_4 at 2 s and cut 1.0-1.5 s.", s.summary)
            assertEquals(2, s.editSummaries.size)
            assertEquals(2, s.rounds)

            val first = mock.takeBody()
            assertRequestShape(first, "claude-opus-5-5")
            val firstText =
                first
                    .messages()[0]
                    .blocks()[0]["text"]!!
                    .jsonPrimitive.content
            assertFalse(firstText.contains("content://"), "asset URIs must not be sent")
            assertFalse(firstText.contains("holiday"), "file names must not be sent")
            assertTrue(firstText.contains("clip_4"))

            val second = mock.takeBody()
            assertRequestShape(second, "claude-opus-5-5")
            val msgs = second.messages()
            assertEquals(3, msgs.size)
            assertEquals("assistant", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
            assertTrue(msgs[1].blocks().any { it.type() == "thinking" }, "thinking block is passed back unchanged")
            val results = msgs[2].blocks()
            assertEquals(
                listOf("tool_result", "tool_result"),
                results.map { it.type() },
                "all results in ONE user message",
            )
            assertEquals(listOf("tu_1", "tu_2"), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
            assertTrue(results.all { it["is_error"]?.jsonPrimitive?.content == "false" })
        }

    @Test
    fun `invalid tool input goes back as is_error and Claude corrects it`() =
        runBlocking {
            // 4.5 s is not inside clip_4 (0-4 s): the timeline rejects it.
            mock.enqueueJson(
                message(listOf(toolUse("tu_bad", "split", """{"clipIds":["clip_4"],"atUs":4500000}""")), "tool_use"),
            )
            mock.enqueueJson(
                message(listOf(toolUse("tu_good", "split", """{"clipIds":["clip_4"],"atUs":2000000}""")), "tool_use"),
            )
            mock.enqueueJson(message(listOf(text("Split clip_4 at 2 s.")), "end_turn"))
            val session = session()

            val s =
                (
                    mock.assistant().suggest(
                        session,
                        "split the first clip in the middle",
                    ) as AiResult.Suggested
                ).suggestion

            assertEquals(1, s.preview.commands.size)
            assertEquals(SplitClip(listOf("clip_4"), 2_000_000), s.preview.commands.single())
            assertEquals(1, s.toolErrors.size)
            assertEquals(AiError.Kind.InvalidToolInput, s.toolErrors.single().kind)
            mock.takeBody()
            val errResult =
                mock
                    .takeBody()
                    .messages()
                    .last()
                    .blocks()
                    .single()
            assertEquals("tu_bad", errResult["tool_use_id"]!!.jsonPrimitive.content)
            assertEquals("true", errResult["is_error"]!!.jsonPrimitive.content)
            assertTrue(errResult["content"].toString().contains("ok"))
            val okResult =
                mock
                    .takeBody()
                    .messages()
                    .last()
                    .blocks()
                    .single()
            assertEquals("false", okResult["is_error"]!!.jsonPrimitive.content)
            assertEquals(3, mock.server.requestCount)
        }

    @Test
    fun `refusal applies nothing and reports the category`() =
        runBlocking {
            mock.enqueueJson(
                message(listOf(toolUse("tu_1", "split", """{"clipIds":["clip_4"],"atUs":2000000}""")), "tool_use"),
            )
            mock.enqueueJson(
                message(
                    emptyList(),
                    "refusal",
                    extra = ""","stop_details":{"type":"refusal","category":"cyber","explanation":"Declined."}""",
                ),
            )
            val session = session()

            val r = mock.assistant().suggest(session, "do something")

            r as AiResult.Refused
            assertEquals("cyber", r.category)
            assertEquals("Declined.", r.explanation)
            assertTrue(r.error.message.contains("cyber"))
            assertEquals(project, session.project)
            assertEquals(0, session.undoHistory.size)
        }

    @Test
    fun `max_tokens stop runs no tool calls from the cut-off reply`() =
        runBlocking {
            mock.enqueueJson(
                message(listOf(toolUse("tu_1", "split", """{"clipIds":["clip_4"],"atUs":2000000}""")), "max_tokens"),
            )
            val s = (mock.assistant().suggest(session(), "split") as AiResult.Suggested).suggestion
            assertFalse(s.complete)
            assertFalse(s.hasEdits)
            assertEquals("max_tokens", s.stopReason)
        }

    @Test
    fun `401 maps to a typed auth error without the key anywhere`() =
        runBlocking {
            mock.enqueueJson(error("authentication_error", "invalid x-api-key"), status = 401)
            val captured = ByteArrayOutputStream()
            val (out, err) = System.out to System.err
            val r =
                try {
                    PrintStream(captured, true).let {
                        System.setOut(it)
                        System.setErr(it)
                    }
                    mock.assistant().suggest(session(), "split")
                } finally {
                    System.setOut(out)
                    System.setErr(err)
                }

            r as AiResult.Failed
            assertEquals(AiError.Kind.Auth, r.error.kind)
            assertEquals(401, r.error.httpStatus)
            assertEquals("req_test", r.error.requestId)
            for (text in listOf(r.toString(), r.error.message, r.error.toString(), captured.toString())) {
                assertFalse(text.contains(Fixtures.CANARY), "key leaked: $text")
                assertFalse(text.contains("CANARYcanary"), "key fragment leaked")
            }
            // The key is sent, and only in the x-api-key header.
            val req = mock.server.takeRequest()
            assertEquals(Fixtures.CANARY, req.getHeader("x-api-key"))
            assertFalse(req.body.readUtf8().contains("CANARY"))
            assertFalse(req.path!!.contains("CANARY"))
            assertNull(req.getHeader("authorization"))
            for ((name, value) in req.headers) {
                if (!name.equals("x-api-key", ignoreCase = true)) assertFalse(value.contains("CANARY"), name)
            }
            val snap = mock.guard.snapshot.value
            assertEquals(MicroUsd.ZERO, snap.reserved)
            assertEquals(MicroUsd.ZERO, snap.sessionSpent, "error responses are released, not charged")
        }

    @Test
    fun `429 overloaded 500 400 and network errors map to typed kinds`() =
        runBlocking {
            mock.enqueueJson(
                error("rate_limit_error", "slow down"),
                status = 429,
                headers = mapOf("retry-after" to "7"),
            )
            val rl = (mock.assistant().suggest(session(), "x") as AiResult.Failed).error
            assertEquals(AiError.Kind.RateLimited, rl.kind)
            assertEquals(7L, rl.retryAfterSeconds)
            assertEquals("slow down", rl.message)

            mock.enqueueJson(error("overloaded_error", "Overloaded"), status = 529)
            assertEquals(
                AiError.Kind.Overloaded,
                (mock.assistant().suggest(session(), "x") as AiResult.Failed).error.kind,
            )

            mock.enqueueJson(error("api_error", "boom"), status = 500)
            assertEquals(AiError.Kind.Server, (mock.assistant().suggest(session(), "x") as AiResult.Failed).error.kind)

            mock.enqueueJson(error("invalid_request_error", "bad ${Fixtures.CANARY}"), status = 400)
            val bad = (mock.assistant().suggest(session(), "x") as AiResult.Failed).error
            assertEquals(AiError.Kind.BadRequest, bad.kind)
            assertFalse(bad.message.contains("CANARY"), "key-shaped text in API messages is redacted")

            mock.server.enqueue(
                okhttp3.mockwebserver
                    .MockResponse()
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START),
            )
            val net = (mock.assistant().suggest(session(), "x") as AiResult.Failed).error
            assertEquals(AiError.Kind.Network, net.kind)
            assertEquals(MicroUsd.ZERO, mock.guard.snapshot.value.sessionSpent)
            assertEquals(MicroUsd.ZERO, mock.guard.snapshot.value.reserved)
        }

    @Test
    fun `missing key fails without a request`() =
        runBlocking {
            mock.keyStore.clear()
            val r = mock.assistant().suggest(session(), "x") as AiResult.Failed
            assertEquals(AiError.Kind.NoApiKey, r.error.kind)
            assertEquals(0, mock.server.requestCount)
        }

    @Test
    fun `spend cap reached sends no request`() =
        runBlocking {
            // $0.10 cap; worst case of one request is 8192 output tokens at $20/MTok = $0.16.
            mock.guard.setCaps(MicroUsd.ofCents(10), null)
            val r = mock.assistant().suggest(session(), "split")
            r as AiResult.CapReached
            assertEquals(SpendDecision.Denied.Reason.SessionCap, r.reason)
            assertNull(r.partial)
            assertEquals(0, mock.server.requestCount)
        }

    @Test
    fun `cap reached mid loop keeps earlier edits as a partial preview`() =
        runBlocking {
            // $0.50 cap: the first reservation fits; the reply then reports 100k input tokens ($0.40), so the
            // second reservation no longer fits and is never sent.
            mock.guard.setCaps(MicroUsd.ofCents(50), null)
            mock.enqueueJson(
                message(
                    listOf(toolUse("tu_1", "split", """{"clipIds":["clip_4"],"atUs":2000000}""")),
                    "tool_use",
                    input = 100_000,
                ),
            )
            val r = mock.assistant().suggest(session(), "split") as AiResult.CapReached
            assertEquals(1, mock.server.requestCount)
            val partial = r.partial!!
            assertEquals(1, partial.preview.commands.size)
        }

    @Test
    fun `cancelling a request in flight charges the worst case and leaves nothing reserved`() =
        runBlocking {
            mock.server.enqueue(
                okhttp3.mockwebserver
                    .MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(message(listOf(text("late")), "end_turn"))
                    .setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS),
            )
            val result = kotlinx.coroutines.withTimeoutOrNull(200) { mock.assistant().suggest(session(), "x") }
            assertNull(result)
            val snap = mock.guard.snapshot.value
            assertEquals(MicroUsd.ZERO, snap.reserved, "no reservation may stay pending after cancel")
            assertTrue(snap.sessionSpent > MicroUsd.ZERO, "a possibly billed request is charged (fail closed)")
        }

    @Test
    fun `a read timeout after sending is charged the worst case, not released`() =
        runBlocking {
            mock.server.enqueue(
                okhttp3.mockwebserver
                    .MockResponse()
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE),
            )
            val config = AssistantConfig(requestTimeout = java.time.Duration.ofMillis(300))
            val r = mock.assistant(config).suggest(session(), "x") as AiResult.Failed
            assertEquals(AiError.Kind.Network, r.error.kind)
            val snap = mock.guard.snapshot.value
            assertEquals(MicroUsd.ZERO, snap.reserved)
            assertTrue(snap.sessionSpent > MicroUsd.ZERO, "a possibly billed request is charged (fail closed)")
        }

    @Test
    fun `usage settles the ledger with real token counts`() =
        runBlocking {
            mock.enqueueJson(
                message(
                    listOf(toolUse("tu_1", "split", """{"clipIds":["clip_4"],"atUs":2000000}""")),
                    "tool_use",
                    input = 1_000,
                    output = 500,
                ),
            )
            mock.enqueueJson(message(listOf(text("Done.")), "end_turn", input = 2_000, output = 100))
            val r = mock.assistant().suggest(session(), "split") as AiResult.Suggested
            // Opus 5.5: $4 / $20 per MTok = 4 / 20 micro-USD per token.
            val expected = MicroUsd(1_000L * 4 + 500L * 20 + 2_000L * 4 + 100L * 20)
            assertEquals(expected, r.cost)
            assertEquals(3_000, r.usage.inputTokens)
            assertEquals(600, r.usage.outputTokens)
            val snap = mock.guard.snapshot.value
            assertEquals(expected, snap.sessionSpent)
            assertEquals(MicroUsd.ZERO, snap.reserved)
        }

    @Test
    fun `fallbacks are only sent for models that accept them`() =
        runBlocking {
            mock.enqueueJson(message(listOf(text("ok")), "end_turn", model = "claude-haiku-5-5"))
            mock.assistant(AssistantConfig(model = "claude-haiku-5-5", maxTokens = 1_024)).suggest(session(), "x")
            val req = mock.server.takeRequest()
            val body =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(req.body.readUtf8())
                    .jsonObject
            assertRequestShape(body, "claude-haiku-5-5", fallbacks = false)
            assertFalse((req.getHeader("anthropic-beta") ?: "").contains("server-side-fallback"))

            mock.enqueueJson(message(listOf(text("ok")), "end_turn"))
            mock.assistant(AssistantConfig(serverSideFallbacks = false)).suggest(session(), "x")
            assertRequestShape(mock.takeBody(), "claude-opus-5-5", fallbacks = false)
        }

    @Test
    fun `fallback-served response from an unpriced model is charged the full reservation`() =
        runBlocking {
            mock.enqueueJson(message(listOf(text("ok")), "end_turn", model = "claude-opus-4-8"))
            val r = mock.assistant().suggest(session(), "x") as AiResult.Suggested
            val snap = mock.guard.snapshot.value
            assertEquals(r.cost, snap.sessionSpent)
            assertTrue(r.cost.value > 160_000, "worst case includes 8192 output tokens at \$20/MTok")
        }

    private fun assertRequestShape(
        body: JsonObject,
        model: String,
        fallbacks: Boolean = true,
    ) {
        assertEquals(model, body["model"]!!.jsonPrimitive.content)
        for (k in listOf("temperature", "top_p", "top_k")) assertFalse(k in body, "$k must not be sent")
        assertFalse(body.toString().contains("budget_tokens"))
        body["thinking"]?.let { assertEquals("adaptive", it.jsonObject["type"]!!.jsonPrimitive.content) }
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("auto"))), body["tool_choice"])
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.toString().contains("microseconds"))
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(EditTools.names, tools.map { it["name"]!!.jsonPrimitive.content }.toSet())
        for (t in tools) {
            assertEquals("true", t["strict"]!!.jsonPrimitive.content, "strict on ${t["name"]}")
            assertFalse(t["input_schema"].toString().contains("\"minimum\""))
        }
        if (fallbacks) {
            assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        } else {
            assertFalse("fallbacks" in body)
        }
        assertFalse(body.toString().contains("CANARY"))
    }
}
