package dev.videoeditor.core.ai.assistant

import dev.videoeditor.core.ai.key.InMemoryApiKeyStore
import dev.videoeditor.core.ai.key.SaveResult
import dev.videoeditor.core.ai.key.SecretString
import dev.videoeditor.core.ai.spend.InMemorySpendLedgerStore
import dev.videoeditor.core.ai.spend.LedgerSpendGuard
import dev.videoeditor.core.ai.spend.MicroUsd
import dev.videoeditor.core.ai.spend.PriceTable
import dev.videoeditor.core.ai.spend.Reservation
import dev.videoeditor.core.ai.spend.SpendDecision
import dev.videoeditor.core.ai.spend.SpendGuard
import dev.videoeditor.core.ai.spend.Usage
import dev.videoeditor.timeline.sample.SampleProjects
import dev.videoeditor.timeline.session.EditorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * MANUAL, SPENDS REAL CREDITS (a fraction of a cent). Excluded from `test`; run only with
 * `ANTHROPIC_API_KEY=... ./gradlew :core:ai:manualRealApiTest` (see core/ai/README.md).
 * Haiku 5.5, max_tokens 1024, hard stop at $0.05 for the whole test.
 */
@Tag("manual-real-api")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ManualRealApiTest {
    /** The ledger's minimum session cap is $0.10, so this wrapper adds the stricter $0.05 test budget. */
    private class HardCap(
        private val delegate: SpendGuard,
        private val cap: MicroUsd,
    ) : SpendGuard by delegate {
        private var committed = MicroUsd.ZERO

        override suspend fun reserve(
            modelId: String,
            estimatedInputTokens: Long,
            maxOutputTokens: Int,
        ): SpendDecision {
            val worst = PriceTable.DEFAULT.priceFor(modelId)?.worstCase(estimatedInputTokens, maxOutputTokens)
            if (worst == null || committed + worst > cap) {
                return SpendDecision.Denied(SpendDecision.Denied.Reason.SessionCap, delegate.snapshot.value)
            }
            committed += worst
            return delegate.reserve(modelId, estimatedInputTokens, maxOutputTokens)
        }

        override suspend fun settle(
            reservation: Reservation,
            modelId: String,
            usage: Usage?,
        ) = delegate.settle(reservation, modelId, usage)
    }

    @Test
    fun `haiku splits a tiny project for real`() =
        runBlocking {
            val keys = InMemoryApiKeyStore()
            val saved = keys.save(SecretString.of(System.getenv("ANTHROPIC_API_KEY")))
            assertEquals(SaveResult.Saved, saved)
            val guard = HardCap(LedgerSpendGuard.open(InMemorySpendLedgerStore()), MicroUsd.ofCents(5))
            val assistant =
                ClaudeEditAssistant(
                    keys,
                    guard,
                    AssistantConfig(model = "claude-haiku-5-5", maxTokens = 1_024, effort = "low", maxIterations = 4),
                    PriceTable.DEFAULT,
                    ClaudeEditAssistant.PRODUCTION_BASE_URL,
                    Dispatchers.IO,
                )
            val r = assistant.suggest(EditorSession(SampleProjects.twoClipDemo()), "Split clip_4 at exactly 2 seconds.")
            println("result: $r") // AiResult never contains the key.
            assertTrue(r is AiResult.Suggested, "expected a suggestion, got $r")
            val s = (r as AiResult.Suggested).suggestion
            assertTrue(s.hasEdits)
            assertTrue(s.cost <= MicroUsd.ofCents(5))
        }
}
