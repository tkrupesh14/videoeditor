package dev.videoeditor.core.ai.spend

/** Local upper estimate of input tokens (docs/design/security.md, A3.2 step 1). */
object TokenEstimator {
    /**
     * Per-image allowance in tokens. Keyframes are capped at 512 px on the long edge (A2.5), which is
     * far below this; the allowance is deliberately generous.
     */
    const val DEFAULT_IMAGE_ALLOWANCE_TOKENS = 1_600L

    /** `ceil(utf8Bytes / 3)` plus a per-image allowance, times 1.2, rounded up. Integer arithmetic only. */
    fun estimateInputTokens(
        utf8Bytes: Long,
        imageCount: Int = 0,
        perImageAllowanceTokens: Long = DEFAULT_IMAGE_ALLOWANCE_TOKENS,
    ): Long {
        require(utf8Bytes >= 0 && imageCount >= 0 && perImageAllowanceTokens >= 0) { "inputs must be >= 0" }
        val raw = Math.addExact(ceilDiv(utf8Bytes, 3), Math.multiplyExact(imageCount.toLong(), perImageAllowanceTokens))
        return ceilDiv(Math.multiplyExact(raw, 6), 5)
    }

    /** Convenience over [estimateInputTokens] for the serialized request text. */
    fun estimateInputTokens(
        requestText: String,
        imageCount: Int = 0,
        perImageAllowanceTokens: Long = DEFAULT_IMAGE_ALLOWANCE_TOKENS,
    ): Long =
        estimateInputTokens(
            requestText.toByteArray(Charsets.UTF_8).size.toLong(),
            imageCount,
            perImageAllowanceTokens,
        )

    /**
     * True when the local worst case is more than half of the remaining budget; the caller should then use
     * `POST /v1/messages/count_tokens` for an exact count before reserving.
     */
    fun shouldUseExactCount(
        worstCase: MicroUsd,
        remaining: MicroUsd,
    ): Boolean = Math.multiplyExact(worstCase.value, 2L) > remaining.value

    private fun ceilDiv(
        a: Long,
        b: Long,
    ): Long = Math.floorDiv(a + b - 1, b)
}
