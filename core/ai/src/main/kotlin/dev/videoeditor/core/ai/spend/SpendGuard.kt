package dev.videoeditor.core.ai.spend

import kotlinx.coroutines.flow.StateFlow

/** Token counts from a Messages API response `usage` object. */
data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
)

data class SpendSnapshot(
    val sessionSpent: MicroUsd,
    val sessionCap: MicroUsd,
    val rollingSpent: MicroUsd,
    val rollingCap: MicroUsd?,
    val reserved: MicroUsd,
    val exhausted: Boolean,
) {
    /** Budget left under the tighter cap after outstanding reservations; never negative. */
    fun remaining(): MicroUsd {
        val session = sessionCap.value - sessionSpent.value - reserved.value
        val rolling = rollingCap?.let { it.value - rollingSpent.value - reserved.value } ?: Long.MAX_VALUE
        return MicroUsd(minOf(session, rolling).coerceAtLeast(0))
    }
}

sealed interface SpendDecision {
    data class Approved(
        val reservation: Reservation,
    ) : SpendDecision

    data class Denied(
        val reason: Reason,
        val snapshot: SpendSnapshot,
    ) : SpendDecision {
        enum class Reason { SessionCap, RollingCap, UnknownModel, LoopLimit }
    }
}

/** Handle for one approved worst-case reservation. Only [SpendGuard] implementations in this module create it. */
class Reservation internal constructor(
    val id: String,
    val worstCase: MicroUsd,
) {
    override fun toString(): String = "Reservation(id=$id, worstCase=$worstCase)"
}

/**
 * Reserve-then-settle spend ledger (docs/design/security.md, A3). A client-side estimate and a safety net,
 * not an invoice.
 */
interface SpendGuard {
    val snapshot: StateFlow<SpendSnapshot>

    /** Call before every request. Atomic check-and-reserve of the worst case. */
    suspend fun reserve(
        modelId: String,
        estimatedInputTokens: Long,
        maxOutputTokens: Int,
    ): SpendDecision

    /** Call exactly once per approved reservation. usage == null means "stream died": charge worst case. */
    suspend fun settle(
        reservation: Reservation,
        modelId: String,
        usage: Usage?,
    )

    /** Request never reached the API. */
    suspend fun release(reservation: Reservation)

    suspend fun startNewSession()

    suspend fun setCaps(
        sessionCap: MicroUsd,
        rollingCap: MicroUsd?,
    )

    /**
     * Addition to the design's signature list: marks the start of a new user instruction, resetting the
     * A3.3 loop limit (at most 12 model round trips per instruction; the 13th reserve is denied with
     * [SpendDecision.Denied.Reason.LoopLimit]).
     */
    suspend fun beginInstruction()

    /** Addition: re-evaluates idle-session expiry and the rolling window against the clock and republishes [snapshot]. */
    suspend fun refresh()
}
