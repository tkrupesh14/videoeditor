package dev.videoeditor.core.ai.spend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PriceTableTest {
    private val table = PriceTable.DEFAULT

    private fun price(id: String) = table.priceFor(id)!!

    @Test
    fun defaultPricesPerMTok() {
        assertEquals(MicroUsd(4_000_000), price("claude-opus-5-5").base.inputPerMTok)
        assertEquals(MicroUsd(20_000_000), price("claude-opus-5-5").base.outputPerMTok)
        assertEquals(MicroUsd(200_000), price("claude-opus-5-5").base.cacheReadPerMTok)
        assertEquals(MicroUsd(2_000_000), price("claude-sonnet-5-5").base.inputPerMTok)
        assertEquals(MicroUsd(10_000_000), price("claude-sonnet-5-5").base.outputPerMTok)
        assertEquals(MicroUsd(100_000), price("claude-sonnet-5-5").base.cacheReadPerMTok)
        assertEquals(MicroUsd(100_000), price("claude-haiku-5-5").base.inputPerMTok)
        assertEquals(MicroUsd(500_000), price("claude-haiku-5-5").base.outputPerMTok)
        assertEquals(MicroUsd(500_000), price("claude-haiku-5-5").longPrompt!!.price.inputPerMTok)
        assertEquals(MicroUsd(2_500_000), price("claude-haiku-5-5").longPrompt!!.price.outputPerMTok)
        assertEquals(MicroUsd(8_000_000), price("claude-fable-5-2").base.inputPerMTok)
        assertEquals(MicroUsd(40_000_000), price("claude-fable-5-2").base.outputPerMTok)
        assertEquals(MicroUsd(200_000), price("claude-fable-5-2").base.cacheReadPerMTok)
    }

    @Test
    fun unknownAndDateSuffixedIdsAreUnknown() {
        assertNull(table.priceFor("claude-opus-5-5-20260401"))
        assertNull(table.priceFor("claude-unknown"))
        assertNull(table.priceFor(""))
        assertNull(table.priceFor("CLAUDE-OPUS-5-5"))
    }

    @Test
    fun worstCaseOpus() {
        // 10_000 in × $4 + 1_000 out × $20 = $0.04 + $0.02
        assertEquals(MicroUsd(60_000), price("claude-opus-5-5").worstCase(10_000, 1_000))
    }

    @Test
    fun worstCaseRoundsUpFractionalMicros() {
        // Haiku: 1 token × 0.1 µUSD -> rounds up to 1 µUSD.
        assertEquals(MicroUsd(1), price("claude-haiku-5-5").worstCase(1, 0))
        assertEquals(MicroUsd(0), price("claude-haiku-5-5").worstCase(0, 0))
        assertEquals(MicroUsd(1), price("claude-haiku-5-5").worstCase(10, 0))
        assertEquals(MicroUsd(2), price("claude-haiku-5-5").worstCase(11, 0))
    }

    @Test
    fun haikuLongPromptTierAppliesAbove100k() {
        val haiku = price("claude-haiku-5-5")
        assertEquals(MicroUsd(10_000), haiku.worstCase(100_000, 0)) // exactly 100K: base tier
        assertEquals(MicroUsd(50_001), haiku.worstCase(100_001, 0)) // above: $0.50 tier, rounded up
        assertEquals(MicroUsd(50_001 + 2_500), haiku.worstCase(100_001, 1_000))
        val usage = Usage(inputTokens = 1_000, outputTokens = 1_000, cacheReadInputTokens = 100_000)
        // prompt 101_000 > 100K: 1_000×0.5 + 1_000×2.5 + 100_000×0.05 = 500 + 2_500 + 5_000
        assertEquals(MicroUsd(8_000), haiku.cost(usage))
    }

    @Test
    fun costPricesEachTokenClass() {
        val opus = price("claude-opus-5-5")
        val usage =
            Usage(
                inputTokens = 1_000,
                outputTokens = 500,
                cacheCreationInputTokens = 2_000,
                cacheReadInputTokens = 10_000,
            )
        // 1_000×4 + 500×20 + 2_000×5 + 10_000×0.2 = 4_000 + 10_000 + 10_000 + 2_000
        assertEquals(MicroUsd(26_000), opus.cost(usage))
    }

    @Test
    fun negativeInputsRejected() {
        assertThrows<IllegalArgumentException> { price("claude-opus-5-5").worstCase(-1, 0) }
        assertThrows<IllegalArgumentException> { price("claude-opus-5-5").worstCase(0, -1) }
        assertThrows<IllegalArgumentException> { price("claude-opus-5-5").cost(Usage(-1, 0)) }
    }

    @Test
    fun microUsdFormattingAndParsing() {
        assertEquals("$0.18", MicroUsd(180_000).toUsdString())
        assertEquals("$1.00", MicroUsd.ofUsd(1).toUsdString())
        assertEquals("$0.01", MicroUsd(5_000).toUsdString())
        assertEquals(MicroUsd(125_000), MicroUsd.parse("0.125"))
        assertEquals(MicroUsd(100_000), MicroUsd.ofCents(10))
        assertThrows<ArithmeticException> { MicroUsd.parse("0.0000001") }
    }

    @Test
    fun tokenEstimator() {
        assertEquals(0, TokenEstimator.estimateInputTokens(0))
        // ceil(10/3)=4, ×1.2 = 4.8 -> 5
        assertEquals(5, TokenEstimator.estimateInputTokens(10))
        // ceil(3000/3)=1000 + 2×1600 = 4200, ×1.2 = 5040
        assertEquals(5_040, TokenEstimator.estimateInputTokens(3_000, imageCount = 2))
        // UTF-8 bytes, not chars: "é" is 2 bytes.
        assertEquals(TokenEstimator.estimateInputTokens(6), TokenEstimator.estimateInputTokens("ééé"))
        assertTrue(TokenEstimator.shouldUseExactCount(MicroUsd(501), MicroUsd(1_000)))
        assertFalse(TokenEstimator.shouldUseExactCount(MicroUsd(500), MicroUsd(1_000)))
    }
}
