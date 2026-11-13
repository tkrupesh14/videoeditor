package dev.videoeditor.core.ai.assistant

import dev.videoeditor.core.ai.key.Fixtures
import dev.videoeditor.core.ai.key.InMemoryApiKeyStore
import dev.videoeditor.core.ai.key.SecretString
import dev.videoeditor.core.ai.spend.InMemorySpendLedgerStore
import dev.videoeditor.core.ai.spend.LedgerSpendGuard
import dev.videoeditor.core.ai.spend.PriceTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A local fake of the Messages API. Nothing here talks to the real API or spends credits. */
class MockAnthropic : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val keyStore = InMemoryApiKeyStore()
    val ledger = InMemorySpendLedgerStore()
    val guard: LedgerSpendGuard = runBlocking { LedgerSpendGuard.open(ledger) }
    val requests = mutableListOf<RecordedRequest>()

    init {
        runBlocking { keyStore.save(SecretString.of(Fixtures.CANARY)) }
    }

    fun assistant(config: AssistantConfig = AssistantConfig()) =
        // Short timeout so a test that sends an unexpected request fails fast instead of hanging.
        ClaudeEditAssistant(
            keyStore,
            guard,
            config.copy(requestTimeout = java.time.Duration.ofSeconds(10)),
            PriceTable.DEFAULT,
            server.url("/").toString().trimEnd('/'),
            Dispatchers.IO,
        )

    fun enqueueJson(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ) {
        val r = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        r.setHeader("request-id", "req_test")
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        server.enqueue(r)
    }

    /** Takes the next recorded request and parses its JSON body. */
    fun takeBody(): JsonObject {
        val r = server.takeRequest()
        requests += r
        return Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    }

    override fun close() {
        server.shutdown()
    }

    companion object {
        fun message(
            content: List<String>,
            stop: String,
            input: Long = 1_000,
            output: Long = 200,
            model: String = "claude-opus-5-5",
            extra: String = "",
        ): String =
            """{"id":"msg_01","type":"message","role":"assistant","model":"$model",""" +
                """"content":[${content.joinToString(",")}],"stop_reason":"$stop","stop_sequence":null$extra,""" +
                """"usage":{"input_tokens":$input,"output_tokens":$output,"cache_creation_input_tokens":0,""" +
                """"cache_read_input_tokens":0}}"""

        fun toolUse(
            id: String,
            name: String,
            input: String,
        ) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

        fun text(t: String) = """{"type":"text","text":"$t"}"""

        fun thinking() = """{"type":"thinking","thinking":"","signature":"sig-abc"}"""

        fun error(
            type: String,
            message: String,
        ) = """{"type":"error","error":{"type":"$type","message":"$message"},"request_id":"req_test"}"""
    }
}
