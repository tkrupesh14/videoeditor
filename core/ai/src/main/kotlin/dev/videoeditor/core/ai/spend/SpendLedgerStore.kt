package dev.videoeditor.core.ai.spend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything [LedgerSpendGuard] persists. Amounts are micro-USD, times are epoch milliseconds.
 * Not secret, but the Android file holding it is excluded from backup so a restore cannot reset it.
 */
@Serializable
data class SpendLedgerState(
    val version: Int = CURRENT_VERSION,
    val sessionCapMicros: Long,
    val rollingCapMicros: Long?,
    val sessionId: String,
    val sessionStartedAtMs: Long,
    val sessionLastActivityMs: Long,
    val sessionSpentMicros: Long,
    /** Rolling-window charges, aggregated per clock hour (bucket start ms) to bound the record size. */
    val hourlyCharges: List<HourlyCharge> = emptyList(),
    /** Reservations not yet settled. Survive process death and are charged in full on the next open. */
    val pending: List<PendingReservation> = emptyList(),
    val roundTripsThisInstruction: Int = 0,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class HourlyCharge(
    val bucketStartMs: Long,
    val micros: Long,
)

@Serializable
data class PendingReservation(
    val id: String,
    val sessionId: String,
    val worstCaseMicros: Long,
    val createdAtMs: Long,
)

/** Persistence for the spend ledger. Implementations must make [save] durable before returning. */
interface SpendLedgerStore {
    /** Null when nothing was saved yet (or the record is unreadable). */
    suspend fun load(): SpendLedgerState?

    suspend fun save(state: SpendLedgerState)
}

class InMemorySpendLedgerStore(
    initial: SpendLedgerState? = null,
) : SpendLedgerStore {
    @Volatile
    var saved: SpendLedgerState? = initial
        private set

    @Volatile
    var saveCount: Int = 0
        private set

    override suspend fun load(): SpendLedgerState? = saved

    override suspend fun save(state: SpendLedgerState) {
        saved = state
        saveCount++
    }
}

/** String form used by key-value stores (Android SharedPreferences). */
object SpendLedgerCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: SpendLedgerState): String = json.encodeToString(SpendLedgerState.serializer(), state)

    /** Null for unreadable or future-version records; the guard then starts a fresh ledger. */
    fun decode(text: String): SpendLedgerState? =
        try {
            json.decodeFromString(SpendLedgerState.serializer(), text).takeIf {
                it.version == SpendLedgerState.CURRENT_VERSION
            }
        } catch (e: IllegalArgumentException) {
            null
        }
}
