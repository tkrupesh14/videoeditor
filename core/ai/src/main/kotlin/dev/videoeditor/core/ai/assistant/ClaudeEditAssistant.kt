package dev.videoeditor.core.ai.assistant

import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.videoeditor.core.ai.key.ApiKeyStore
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.PriceTable
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.core.ai.spend.TokenEstimator
import dev.videoeditor.core.ai.spend.Usage
import dev.videoeditor.timeline.json.TimelineJson
import dev.videoeditor.timeline.schema.CommandResponse
import dev.videoeditor.timeline.schema.ToolDispatcher
import dev.videoeditor.timeline.session.EditPreview
import dev.videoeditor.timeline.session.EditorSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.util.Base64

/** Settings for [ClaudeEditAssistant]. Sampling parameters and thinking budgets are deliberately absent. */
data class AssistantConfig(
    val model: String = DEFAULT_MODEL,
    /** Hard ceiling on output (thinking included) per request; also the spend guard's worst case. */
    val maxTokens: Int = 8_192,
    /** Sent as `output_config.effort`: one of low, medium, high, xhigh, max. */
    val effort: String = "medium",
    /** Model round trips per instruction (the spend guard also enforces its own limit of 12). */
    val maxIterations: Int = 8,
    /** At most [Keyframes.MAX_PER_REQUEST] regardless. */
    val maxKeyframes: Int = Keyframes.MAX_PER_REQUEST,
    /**
     * Server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). Opt-in on
     * the API, default on here; only sent for models in [FALLBACK_MODELS] because others return 400.
     */
    val serverSideFallbacks: Boolean = true,
    val requestTimeout: Duration = Duration.ofMinutes(5),
) {
    init {
        require(maxTokens in 1..128_000) { "maxTokens out of range" }
        require(effort in EFFORTS) { "effort must be one of $EFFORTS" }
        require(maxIterations in 1..12) { "maxIterations must be 1..12" }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val EFFORTS: Set<String> = setOf("low", "medium", "high", "xhigh", "max")

        /** Models documented to accept server-side `fallbacks` (Haiku 5.5 rejects it with a 400). */
        val FALLBACK_MODELS: Set<String> =
            setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-fable-5-1")
    }
}

/**
 * Turns a user instruction into an [AiSuggestion]: a preview built on a copy of the project by running
 * Claude's tool calls through the timeline's [ToolDispatcher] (manual tool loop, docs/design/security.md
 * A2/A3). Every request is reserved against the [SpendGuard] first and settled with the real `usage` after.
 */
class ClaudeEditAssistant internal constructor(
    keyStore: ApiKeyStore,
    private val spendGuard: SpendGuard,
    private val config: AssistantConfig,
    private val priceTable: PriceTable,
    baseUrl: String,
    io: CoroutineDispatcher,
) {
    /** Production constructor: requests go only to [PRODUCTION_BASE_URL]. */
    constructor(
        keyStore: ApiKeyStore,
        spendGuard: SpendGuard,
        config: AssistantConfig = AssistantConfig(),
        priceTable: PriceTable = PriceTable.DEFAULT,
    ) : this(keyStore, spendGuard, config, priceTable, PRODUCTION_BASE_URL, Dispatchers.IO)

    private val auth = AnthropicAuth(keyStore, baseUrl, config.requestTimeout, io)
    private val dispatcher = ToolDispatcher(exportService = null, transcriptService = null)

    private class Run(
        val preview: EditPreview,
    ) {
        var usage = Usage(0, 0)
        var cost = MicroUsd.ZERO
        var rounds = 0
        var lastStop: String? = null
        val edits = mutableListOf<String>()
        val toolErrors = mutableListOf<AiError>()

        fun suggestion(
            summary: String?,
            complete: Boolean,
        ) = AiSuggestion(
            preview = preview,
            summary = summary?.takeIf { it.isNotBlank() } ?: edits.joinToString("\n").ifEmpty { "No edits." },
            editSummaries = edits.toList(),
            toolErrors = toolErrors.toList(),
            usage = usage,
            cost = cost,
            complete = complete,
            stopReason = lastStop,
            rounds = rounds,
        )

        fun partial(): AiSuggestion? = if (preview.commands.isEmpty()) null else suggestion(null, complete = false)
    }

    suspend fun suggest(
        session: EditorSession,
        instruction: String,
        analysis: AnalysisBundle = AnalysisBundle(),
    ): AiResult {
        require(instruction.isNotBlank()) { "instruction must not be blank" }
        val run = Run(session.newPreview())
        spendGuard.beginInstruction()

        val keyframes = Keyframes.accept(analysis.keyframes, config.maxKeyframes)
        val first =
            mutableListOf(BetaContentBlockParam.ofText(Prompts.userText(run.preview.project, instruction, analysis)))
        var imageBase64Chars = 0L
        for (k in keyframes) {
            val b64 = Base64.getEncoder().encodeToString(k.jpeg)
            imageBase64Chars += b64.length
            first += BetaContentBlockParam.ofText("Keyframe at ${k.timeUs} µs:")
            first +=
                BetaContentBlockParam.ofImage(
                    BetaBase64ImageSource
                        .builder()
                        .data(b64)
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
        }
        val messages = mutableListOf(BetaMessageParam.ofUserOfContentBlockParams(first))

        while (run.rounds < config.maxIterations) {
            val params = buildParams(messages)
            val bodyBytes = jsonMapper().writeValueAsBytes(params._body()).size.toLong()
            val estimate =
                TokenEstimator.estimateInputTokens(
                    (bodyBytes - imageBase64Chars).coerceAtLeast(0),
                    keyframes.size,
                )
            val reservation =
                when (val d = spendGuard.reserve(config.model, estimate, config.maxTokens)) {
                    is SpendDecision.Denied -> return AiResult.CapReached(
                        d.reason,
                        d.snapshot,
                        run.partial(),
                        run.usage,
                        run.cost,
                    )
                    is SpendDecision.Approved -> d.reservation
                }
            val response: BetaMessage =
                try {
                    auth.create(params)
                } catch (e: AnthropicAuth.MissingKeyException) {
                    spendGuard.release(reservation)
                    return failed(
                        run,
                        AiError(AiError.Kind.NoApiKey, "No Anthropic API key is saved. Add one in settings."),
                    )
                } catch (e: AnthropicServiceException) {
                    spendGuard.release(reservation) // Error responses are not billed.
                    return failed(run, ErrorMapping.fromService(e))
                } catch (e: AnthropicIoException) {
                    spendGuard.release(reservation) // No response arrived (A3.3).
                    return failed(run, ErrorMapping.network())
                } catch (e: AnthropicInvalidDataException) {
                    spendGuard.settle(reservation, config.model, null) // A response arrived; charge pessimistically.
                    return failed(run, ErrorMapping.invalidResponse())
                } catch (e: AnthropicException) {
                    spendGuard.release(reservation)
                    return failed(run, ErrorMapping.network())
                }
            run.rounds++

            // Settle with real usage before anything else. A fallback model missing from the price table is
            // charged the full reservation by the guard (fail closed).
            val usage = readUsage(response)
            val servedBy = response.model().asString()
            spendGuard.settle(reservation, servedBy, usage)
            run.usage = run.usage + usage
            run.cost += priceTable.priceFor(servedBy)?.cost(usage) ?: reservation.worstCase

            val stop = response.stopReason().orElse(null)
            run.lastStop = stop?.asString()
            when (stop) {
                BetaStopReason.REFUSAL -> {
                    val details = response.stopDetails().orElse(null)
                    return AiResult.Refused(
                        category = details?.category()?.orElse(null)?.asString(),
                        explanation = details?.explanation()?.orElse(null),
                        usage = run.usage,
                        cost = run.cost,
                    )
                }
                BetaStopReason.MAX_TOKENS, BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> {
                    // Output was cut off; tool calls in it may be incomplete, so none of them is run.
                    val note =
                        "Claude's reply was cut off (${stop.asString()}); only the edits made before that are included."
                    return AiResult.Suggested(
                        run.suggestion(listOfNotNull(text(response), note).joinToString("\n"), complete = false),
                    )
                }
                BetaStopReason.TOOL_USE -> {
                    messages += response.toParam()
                    messages += BetaMessageParam.ofUserOfContentBlockParams(runTools(run, response))
                }
                else -> return AiResult.Suggested(run.suggestion(text(response), complete = true))
            }
        }
        return AiResult.Suggested(
            run.suggestion(
                "Stopped after ${config.maxIterations} rounds; the edits so far are in the preview.",
                complete = false,
            ),
        )
    }

    private fun failed(
        run: Run,
        error: AiError,
    ) = AiResult.Failed(error, run.partial(), run.usage, run.cost)

    private fun buildParams(messages: List<BetaMessageParam>): MessageCreateParams {
        val b =
            MessageCreateParams
                .builder()
                .model(config.model)
                .maxTokens(config.maxTokens.toLong())
                .system(Prompts.SYSTEM)
                .messages(messages)
                .toolChoice(BetaToolChoiceAuto.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(config.effort)).build())
        // Thinking: omitted, which is adaptive on current models. No temperature/top_p/top_k, no budget_tokens.
        EditTools.betaTools.forEach { b.addTool(it) }
        if (config.serverSideFallbacks && config.model in AssistantConfig.FALLBACK_MODELS) {
            b.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault()
        }
        return b.build()
    }

    /** Runs every tool_use block against the preview; all results go back in one user message. */
    private suspend fun runTools(
        run: Run,
        response: BetaMessage,
    ): List<BetaContentBlockParam> =
        response.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
            val input = runCatching { EditTools.fromSdk(call._input()) }.getOrNull()
            val (isError, content) =
                when {
                    call.name() !in EditTools.names ->
                        true to
                            """{"ok":false,"error":{"type":"unknown_tool","message":"Unknown tool: ${call.name()}"}}"""
                    input !is JsonObject ->
                        true to
                            """{"ok":false,"error":{"type":"malformed","message":"Tool input must be a JSON object"}}"""
                    else -> {
                        val r = dispatcher.call(run.preview, call.name(), input)
                        r.isError to r.content.toString()
                    }
                }
            if (isError) {
                run.toolErrors += AiError(AiError.Kind.InvalidToolInput, "${call.name()}: ${errorMessage(content)}")
            } else {
                summaryOf(content)?.let { run.edits += it }
            }
            BetaContentBlockParam.ofToolResult(
                BetaToolResultBlockParam
                    .builder()
                    .toolUseId(call.id())
                    .content(content)
                    .isError(isError)
                    .build(),
            )
        }

    private fun summaryOf(content: String): String? =
        runCatching { TimelineJson.json.decodeFromString(CommandResponse.serializer(), content).summary }.getOrNull()

    private fun errorMessage(content: String): String =
        runCatching {
            TimelineJson.json
                .decodeFromString(CommandResponse.serializer(), content)
                .error
                ?.get("message")
                ?.toString()
                ?.trim('"')
        }.getOrNull() ?: "rejected"

    private fun text(response: BetaMessage): String? =
        response
            .content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("\n")
            .trim()
            .ifEmpty { null }

    private fun readUsage(response: BetaMessage): Usage {
        val u = response.usage()
        return Usage(
            inputTokens = u.inputTokens(),
            outputTokens = u.outputTokens(),
            cacheCreationInputTokens = u.cacheCreationInputTokens().orElse(0L),
            cacheReadInputTokens = u.cacheReadInputTokens().orElse(0L),
        )
    }

    private operator fun Usage.plus(o: Usage) =
        Usage(
            inputTokens + o.inputTokens,
            outputTokens + o.outputTokens,
            cacheCreationInputTokens + o.cacheCreationInputTokens,
            cacheReadInputTokens + o.cacheReadInputTokens,
        )

    companion object {
        const val PRODUCTION_BASE_URL = "https://api.anthropic.com"
    }
}
