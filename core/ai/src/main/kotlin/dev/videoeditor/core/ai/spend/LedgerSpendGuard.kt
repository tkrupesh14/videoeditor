package dev.videoeditor.core.ai.spend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Persistent [SpendGuard] (docs/design/security.md, A3).
 *
 * - All state changes run under one [Mutex], so concurrent reservations cannot jointly exceed a cap.
 * - A reservation is persisted before it is approved; if persisting fails the exception propagates and
 *   nothing is reserved (fail closed, the request is not sent).
 * - Reservations still pending when the process died are charged in full on the next [open]
 *   (same rule as a stream that died without usage).
 * - The session ends after [SESSION_IDLE] without activity; the rolling cap covers [ROLLING_WINDOW],
 *   counted in whole clock hours (a charge leaves the window once its whole hour is older than 30 days).
 */
class LedgerSpendGuard private constructor(
    private val store: SpendLedgerStore,
    private val priceTable: PriceTable,
    private val clock: Clock,
    private val newId: () -> String,
    private val maxRoundTripsPerInstruction: Int,
    initial: SpendLedgerState,
) : SpendGuard {
    private val mutex = Mutex()
    private var state: SpendLedgerState = initial
    private val mutableSnapshot = MutableStateFlow(snapshotOf(initial, clock.millis()))

    override val snapshot: StateFlow<SpendSnapshot> = mutableSnapshot.asStateFlow()

    override suspend fun reserve(
        modelId: String,
        estimatedInputTokens: Long,
        maxOutputTokens: Int,
    ): SpendDecision =
        mutex.withLock {
            val now = clock.millis()
            val base = maintain(state, now)
            val snap = snapshotOf(base, now)
            val price = priceTable.priceFor(modelId)
            val denial =
                when {
                    price == null -> SpendDecision.Denied.Reason.UnknownModel
                    base.roundTripsThisInstruction >= maxRoundTripsPerInstruction ->
                        SpendDecision.Denied.Reason.LoopLimit
                    else -> null
                }
            if (denial != null) {
                commitAfterSave(base, now)
                return@withLock SpendDecision.Denied(denial, snap)
            }
            val worst = price!!.worstCase(estimatedInputTokens, maxOutputTokens).value
            val reserved = base.pending.sumOf { it.worstCaseMicros }
            val sessionAfter = Math.addExact(Math.addExact(base.sessionSpentMicros, reserved), worst)
            if (snap.sessionSpent >= snap.sessionCap || sessionAfter > base.sessionCapMicros) {
                commitAfterSave(base, now)
                return@withLock SpendDecision.Denied(SpendDecision.Denied.Reason.SessionCap, snap)
            }
            val rollingCap = base.rollingCapMicros
            if (rollingCap != null) {
                val rollingAfter = Math.addExact(Math.addExact(snap.rollingSpent.value, reserved), worst)
                if (snap.rollingSpent.value >= rollingCap || rollingAfter > rollingCap) {
                    commitAfterSave(base, now)
                    return@withLock SpendDecision.Denied(SpendDecision.Denied.Reason.RollingCap, snap)
                }
            }
            val id = newId()
            val next =
                base.copy(
                    pending = base.pending + PendingReservation(id, base.sessionId, worst, now),
                    roundTripsThisInstruction = base.roundTripsThisInstruction + 1,
                    sessionLastActivityMs = now,
                )
            commitAfterSave(next, now)
            SpendDecision.Approved(Reservation(id, MicroUsd(worst)))
        }

    override suspend fun settle(
        reservation: Reservation,
        modelId: String,
        usage: Usage?,
    ) {
        mutex.withLock {
            val now = clock.millis()
            val pending = state.pending.firstOrNull { it.id == reservation.id } ?: return@withLock
            val price = priceTable.priceFor(modelId)
            val charge =
                if (usage == null || price == null) pending.worstCaseMicros else price.cost(usage).value
            val withoutPending = state.copy(pending = state.pending - pending, sessionLastActivityMs = now)
            commitEvenIfSaveFails(charge(withoutPending, pending.sessionId, charge, now), now)
        }
    }

    override suspend fun release(reservation: Reservation) {
        mutex.withLock {
            val now = clock.millis()
            val pending = state.pending.firstOrNull { it.id == reservation.id } ?: return@withLock
            val trips =
                if (pending.sessionId == state.sessionId) {
                    (state.roundTripsThisInstruction - 1).coerceAtLeast(0)
                } else {
                    state.roundTripsThisInstruction
                }
            commitEvenIfSaveFails(
                state.copy(pending = state.pending - pending, roundTripsThisInstruction = trips),
                now,
            )
        }
    }

    override suspend fun startNewSession() {
        mutex.withLock {
            val now = clock.millis()
            commitEvenIfSaveFails(newSession(state, now), now)
        }
    }

    override suspend fun setCaps(
        sessionCap: MicroUsd,
        rollingCap: MicroUsd?,
    ) {
        require(sessionCap in SESSION_CAP_RANGE) { "sessionCap must be between \$0.10 and \$50" }
        require(rollingCap == null || rollingCap in ROLLING_CAP_RANGE) {
            "rollingCap must be between \$1 and \$500, or null for off"
        }
        mutex.withLock {
            val now = clock.millis()
            commitEvenIfSaveFails(
                state.copy(sessionCapMicros = sessionCap.value, rollingCapMicros = rollingCap?.value),
                now,
            )
        }
    }

    override suspend fun beginInstruction() {
        mutex.withLock {
            val now = clock.millis()
            commitEvenIfSaveFails(maintain(state, now).copy(roundTripsThisInstruction = 0), now)
        }
    }

    override suspend fun refresh() {
        mutex.withLock {
            val now = clock.millis()
            val next = maintain(state, now)
            if (next != state) commitEvenIfSaveFails(next, now) else publish(now)
        }
    }

    /** Session idle expiry and rolling-window pruning. */
    private fun maintain(
        s: SpendLedgerState,
        now: Long,
    ): SpendLedgerState {
        val active = if (now - s.sessionLastActivityMs >= SESSION_IDLE.toMillis()) newSession(s, now) else s
        val windowStart = now - ROLLING_WINDOW.toMillis()
        val kept = active.hourlyCharges.filter { it.bucketStartMs + HOUR_MS > windowStart }
        return if (kept.size == active.hourlyCharges.size) active else active.copy(hourlyCharges = kept)
    }

    private fun newSession(
        s: SpendLedgerState,
        now: Long,
    ): SpendLedgerState =
        s.copy(
            sessionId = newId(),
            sessionStartedAtMs = now,
            sessionLastActivityMs = now,
            sessionSpentMicros = 0,
            roundTripsThisInstruction = 0,
        )

    private suspend fun commitAfterSave(
        next: SpendLedgerState,
        now: Long,
    ) {
        if (next != state) store.save(next)
        state = next
        publish(now)
    }

    /** Charges and releases must never be lost in memory, even if the disk write fails. */
    private suspend fun commitEvenIfSaveFails(
        next: SpendLedgerState,
        now: Long,
    ) {
        state = next
        publish(now)
        store.save(next)
    }

    private fun publish(now: Long) {
        mutableSnapshot.value = snapshotOf(state, now)
    }

    companion object {
        val DEFAULT_SESSION_CAP: MicroUsd = MicroUsd.ofUsd(1)
        val DEFAULT_ROLLING_CAP: MicroUsd = MicroUsd.ofUsd(20)
        val SESSION_CAP_RANGE: ClosedRange<MicroUsd> = MicroUsd.ofCents(10)..MicroUsd.ofUsd(50)
        val ROLLING_CAP_RANGE: ClosedRange<MicroUsd> = MicroUsd.ofUsd(1)..MicroUsd.ofUsd(500)
        val SESSION_IDLE: Duration = Duration.ofHours(12)
        val ROLLING_WINDOW: Duration = Duration.ofDays(30)
        const val MAX_ROUND_TRIPS_PER_INSTRUCTION = 12
        private const val HOUR_MS = 3_600_000L

        /**
         * Loads the ledger (or starts a fresh one with default caps), charges any reservation left pending by
         * a previous process in full, applies session idle expiry, and persists the result.
         */
        suspend fun open(
            store: SpendLedgerStore,
            priceTable: PriceTable = PriceTable.DEFAULT,
            clock: Clock = Clock.systemUTC(),
            newId: () -> String = { UUID.randomUUID().toString() },
            maxRoundTripsPerInstruction: Int = MAX_ROUND_TRIPS_PER_INSTRUCTION,
        ): LedgerSpendGuard {
            require(maxRoundTripsPerInstruction > 0) { "maxRoundTripsPerInstruction must be positive" }
            val now = clock.millis()
            val loaded =
                store.load() ?: SpendLedgerState(
                    sessionCapMicros = DEFAULT_SESSION_CAP.value,
                    rollingCapMicros = DEFAULT_ROLLING_CAP.value,
                    sessionId = newId(),
                    sessionStartedAtMs = now,
                    sessionLastActivityMs = now,
                    sessionSpentMicros = 0,
                )
            var recovered = loaded.copy(pending = emptyList())
            for (p in loaded.pending) recovered = charge(recovered, p.sessionId, p.worstCaseMicros, now)
            val guard = LedgerSpendGuard(store, priceTable, clock, newId, maxRoundTripsPerInstruction, recovered)
            guard.mutex.withLock {
                val next = guard.maintain(guard.state, now)
                guard.state = next
                store.save(next)
                guard.publish(now)
            }
            return guard
        }

        private fun charge(
            s: SpendLedgerState,
            sessionId: String,
            micros: Long,
            now: Long,
        ): SpendLedgerState {
            if (micros == 0L) return s
            val bucket = Math.floorDiv(now, HOUR_MS) * HOUR_MS
            val existing = s.hourlyCharges.firstOrNull { it.bucketStartMs == bucket }
            val buckets =
                if (existing == null) {
                    s.hourlyCharges + HourlyCharge(bucket, micros)
                } else {
                    s.hourlyCharges.map {
                        if (it === existing) it.copy(micros = Math.addExact(it.micros, micros)) else it
                    }
                }
            val sessionSpent =
                if (sessionId == s.sessionId) Math.addExact(s.sessionSpentMicros, micros) else s.sessionSpentMicros
            return s.copy(hourlyCharges = buckets, sessionSpentMicros = sessionSpent)
        }

        private fun snapshotOf(
            s: SpendLedgerState,
            now: Long,
        ): SpendSnapshot {
            val windowStart = now - ROLLING_WINDOW.toMillis()
            val rolling = s.hourlyCharges.filter { it.bucketStartMs + HOUR_MS > windowStart }.sumOf { it.micros }
            val sessionExhausted = s.sessionSpentMicros >= s.sessionCapMicros
            val rollingExhausted = s.rollingCapMicros != null && rolling >= s.rollingCapMicros
            return SpendSnapshot(
                sessionSpent = MicroUsd(s.sessionSpentMicros),
                sessionCap = MicroUsd(s.sessionCapMicros),
                rollingSpent = MicroUsd(rolling),
                rollingCap = s.rollingCapMicros?.let { MicroUsd(it) },
                reserved = MicroUsd(s.pending.sumOf { it.worstCaseMicros }),
                exhausted = sessionExhausted || rollingExhausted,
            )
        }
    }
}
