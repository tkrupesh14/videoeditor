package dev.videoeditor.core.ai.assistant

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.videoeditor.core.ai.key.ApiKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * The one place the API key is read (docs/design/security.md, A2.1). For each request it loads the key,
 * builds a client that carries it for that single request, closes the [dev.videoeditor.core.ai.key.SecretString]
 * and, after the call, the client. No long-lived object holds the key. SDK retries are off (the caller
 * decides, and every attempt must pass the spend guard) and SDK logging is forced off regardless of
 * `ANTHROPIC_LOG`.
 */
internal class AnthropicAuth(
    private val keyStore: ApiKeyStore,
    private val baseUrl: String,
    private val timeout: Duration,
    private val io: CoroutineDispatcher,
) {
    companion object {
        const val API_KEY_HEADER = "x-api-key"
    }

    class MissingKeyException : Exception("No Anthropic API key is available")

    suspend fun create(params: MessageCreateParams): BetaMessage {
        val secret = keyStore.load() ?: throw MissingKeyException()
        val client =
            try {
                secret.use { key ->
                    AnthropicOkHttpClient
                        .builder()
                        // anthropic-java 3.8 sends .apiKey() as "Authorization: Bearer"; the design (A2.1)
                        // requires the x-api-key header, so it is set directly on this per-request client.
                        .putHeader(API_KEY_HEADER, key.toString())
                        .baseUrl(baseUrl)
                        .maxRetries(0)
                        .timeout(timeout)
                        .logLevel(LogLevel.OFF)
                        .build()
                }
            } finally {
                secret.close()
            }
        return try {
            withContext(io) { client.beta().messages().create(params) }
        } finally {
            client.close()
        }
    }
}
