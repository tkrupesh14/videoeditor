package dev.videoeditor.core.ai.spend

/**
 * Prices for one tier, each in micro-USD per million tokens. Because 1 USD per MTok equals
 * 1 micro-USD per token, a value of 4_000_000 here means $4.00 / MTok.
 */
data class TierPrice(
    val inputPerMTok: MicroUsd,
    val outputPerMTok: MicroUsd,
    val cacheWritePerMTok: MicroUsd,
    val cacheReadPerMTok: MicroUsd,
)

/** A higher-priced tier that applies when the prompt is strictly longer than [thresholdPromptTokens]. */
data class LongPromptTier(
    val thresholdPromptTokens: Long,
    val price: TierPrice,
)

data class ModelPrice(
    val base: TierPrice,
    val longPrompt: LongPromptTier? = null,
) {
    private fun tierFor(promptTokens: Long): TierPrice =
        if (longPrompt != null && promptTokens > longPrompt.thresholdPromptTokens) longPrompt.price else base

    /**
     * A3.2 step 2: `inputTokens × inputPrice (highest applicable tier, uncached) + max_tokens × outputPrice`.
     * Rounded up to the next micro-USD.
     */
    fun worstCase(
        estimatedInputTokens: Long,
        maxOutputTokens: Int,
    ): MicroUsd {
        require(estimatedInputTokens >= 0) { "estimatedInputTokens must be >= 0" }
        require(maxOutputTokens >= 0) { "maxOutputTokens must be >= 0" }
        val tier = tierFor(estimatedInputTokens)
        val numerator =
            Math.addExact(
                Math.multiplyExact(estimatedInputTokens, tier.inputPerMTok.value),
                Math.multiplyExact(maxOutputTokens.toLong(), tier.outputPerMTok.value),
            )
        return MicroUsd(ceilDiv(numerator, TOKENS_PER_MTOK))
    }

    /**
     * A3.3: actual cost from API `usage`, each token class at its own price. The long-prompt tier applies
     * when the whole prompt (uncached + cache write + cache read) exceeds the threshold.
     */
    fun cost(usage: Usage): MicroUsd {
        require(
            usage.inputTokens >= 0 &&
                usage.outputTokens >= 0 &&
                usage.cacheCreationInputTokens >= 0 &&
                usage.cacheReadInputTokens >= 0,
        ) { "usage counts must be >= 0" }
        val prompt =
            Math.addExact(
                Math.addExact(usage.inputTokens, usage.cacheCreationInputTokens),
                usage.cacheReadInputTokens,
            )
        val tier = tierFor(prompt)
        var numerator = Math.multiplyExact(usage.inputTokens, tier.inputPerMTok.value)
        numerator = Math.addExact(numerator, Math.multiplyExact(usage.outputTokens, tier.outputPerMTok.value))
        numerator =
            Math.addExact(numerator, Math.multiplyExact(usage.cacheCreationInputTokens, tier.cacheWritePerMTok.value))
        numerator =
            Math.addExact(numerator, Math.multiplyExact(usage.cacheReadInputTokens, tier.cacheReadPerMTok.value))
        return MicroUsd(ceilDiv(numerator, TOKENS_PER_MTOK))
    }

    private companion object {
        const val TOKENS_PER_MTOK = 1_000_000L

        fun ceilDiv(
            a: Long,
            b: Long,
        ): Long = Math.floorDiv(a + b - 1, b)
    }
}

/**
 * USD-per-million-token prices keyed by exact model ID (A3.1). A model ID that is not in the table is
 * refused by [SpendGuard]; it is never priced at zero or guessed (date-suffixed or aliased IDs are unknown too).
 */
class PriceTable(
    private val prices: Map<String, ModelPrice>,
) {
    fun priceFor(modelId: String): ModelPrice? = prices[modelId]

    val modelIds: Set<String> get() = prices.keys

    companion object {
        /** Date the default values were checked against Anthropic's published prices. Re-verify each release. */
        const val VERIFIED_ON = "2026-11-13"

        private fun usd(value: String) = MicroUsd.parse(value)

        /**
         * Cache writes are priced at 1.25× input (Anthropic's default 5-minute cache TTL; `usage` does not
         * split TTLs in [Usage], and this client does not use the 1-hour TTL). Cache reads per model as
         * published; Haiku 5.5's cache read uses the standard 0.1× input multiplier.
         */
        val DEFAULT: PriceTable =
            PriceTable(
                mapOf(
                    "claude-opus-5-5" to
                        ModelPrice(TierPrice(usd("4.00"), usd("20.00"), usd("5.00"), usd("0.20"))),
                    "claude-sonnet-5-5" to
                        ModelPrice(TierPrice(usd("2.00"), usd("10.00"), usd("2.50"), usd("0.10"))),
                    "claude-haiku-5-5" to
                        ModelPrice(
                            base = TierPrice(usd("0.10"), usd("0.50"), usd("0.125"), usd("0.01")),
                            longPrompt =
                                LongPromptTier(
                                    thresholdPromptTokens = 100_000,
                                    price = TierPrice(usd("0.50"), usd("2.50"), usd("0.625"), usd("0.05")),
                                ),
                        ),
                    "claude-fable-5-2" to
                        ModelPrice(TierPrice(usd("8.00"), usd("40.00"), usd("10.00"), usd("0.20"))),
                ),
            )
    }
}
