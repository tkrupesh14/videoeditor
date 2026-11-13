package dev.videoeditor.core.ai.spend

import dev.videoeditor.core.ai.spend.SpendDecision.Denied.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class LedgerSpendGuardTest {
    private class MutableClock(
        var nowMs: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

        fun advance(d: Duration) {
            nowMs += d.toMillis()
        }
    }

    // Hour-aligned start so rolling-window bucket arithmetic is exact in tests.
    private val start = 1_700_000_000_000L / 3_600_000L * 3_600_000L
    private val clock = MutableClock(start)
    private val ids = AtomicInteger()
    private val store = InMemorySpendLedgerStore()

    private val opus = "claude-opus-5-5"

    private fun guard(
        s: SpendLedgerStore = store,
        maxRoundTrips: Int = LedgerSpendGuard.MAX_ROUND_TRIPS_PER_INSTRUCTION,
    ) = runBlocking {
        LedgerSpendGuard.open(s, PriceTable.DEFAULT, clock, { "id-${ids.incrementAndGet()}" }, maxRoundTrips)
    }

    /** Opus output-only reservation of exactly [cents] cents: maxOutput tokens × $20/MTok. */
    private fun outTokensForCents(cents: Long): Int = (cents * 10_000 / 20).toInt()

    private fun approved(d: SpendDecision): Reservation = (d as SpendDecision.Approved).reservation

    @Test
    fun defaults() {
        val g = guard()
        val s = g.snapshot.value
        assertEquals(MicroUsd.ofUsd(1), s.sessionCap)
        assertEquals(MicroUsd.ofUsd(20), s.rollingCap)
        assertEquals(MicroUsd.ZERO, s.sessionSpent)
        assertEquals(MicroUsd.ZERO, s.reserved)
        assertFalse(s.exhausted)
    }

    @Test
    fun reserveThenSettleChargesActualUsage() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 10_000, 1_000))
            assertEquals(MicroUsd(60_000), r.worstCase)
            assertEquals(MicroUsd(60_000), g.snapshot.value.reserved)
            g.settle(r, opus, Usage(inputTokens = 5_000, outputTokens = 100))
            val s = g.snapshot.value
            assertEquals(MicroUsd(22_000), s.sessionSpent) // 5_000×4 + 100×20
            assertEquals(MicroUsd(22_000), s.rollingSpent)
            assertEquals(MicroUsd.ZERO, s.reserved)
        }

    @Test
    fun settleIsIdempotent() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 0, 1_000))
            g.settle(r, opus, Usage(0, 1_000))
            g.settle(r, opus, Usage(0, 1_000))
            g.release(r)
            assertEquals(MicroUsd(20_000), g.snapshot.value.sessionSpent)
        }

    @Test
    fun deadStreamChargesFullReservation() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 10_000, 1_000))
            g.settle(r, opus, usage = null)
            assertEquals(MicroUsd(60_000), g.snapshot.value.sessionSpent)
        }

    @Test
    fun releaseChargesNothing() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 10_000, 1_000))
            g.release(r)
            assertEquals(MicroUsd.ZERO, g.snapshot.value.sessionSpent)
            assertEquals(MicroUsd.ZERO, g.snapshot.value.reserved)
        }

    @Test
    fun unknownModelIsRefused() =
        runBlocking {
            val g = guard()
            val d = g.reserve("claude-made-up-9", 10, 10)
            assertEquals(Reason.UnknownModel, (d as SpendDecision.Denied).reason)
            assertEquals(MicroUsd.ZERO, g.snapshot.value.reserved)
            val d2 = g.reserve("claude-opus-5-5-20260401", 10, 10)
            assertEquals(Reason.UnknownModel, (d2 as SpendDecision.Denied).reason)
        }

    @Test
    fun sessionCapBoundaryExactlyAtCapIsAllowedOneMicroOverIsDenied() =
        runBlocking {
            val g = guard()
            // $1.00 cap. Reserve exactly $1.00.
            val r = approved(g.reserve(opus, 0, outTokensForCents(100)))
            assertEquals(MicroUsd.ofUsd(1), r.worstCase)
            // Anything more is denied while the reservation is outstanding.
            val d = g.reserve("claude-haiku-5-5", 1, 0)
            assertEquals(Reason.SessionCap, (d as SpendDecision.Denied).reason)
            g.release(r)
            // $1.00 + 1 micro is denied outright.
            val over = g.reserve(opus, 1, outTokensForCents(100))
            assertEquals(Reason.SessionCap, (over as SpendDecision.Denied).reason)
        }

    @Test
    fun spentAtCapMakesGuardExhaustedAndDeniesEvenZeroCost() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 0, outTokensForCents(100)))
            g.settle(r, opus, null)
            assertTrue(g.snapshot.value.exhausted)
            val d = g.reserve(opus, 0, 0)
            assertEquals(Reason.SessionCap, (d as SpendDecision.Denied).reason)
            assertTrue(d.snapshot.exhausted)
        }

    @Test
    fun actualUsageAboveReservationIsChargedAndExhausts() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 0, 1_000))
            g.settle(r, opus, Usage(inputTokens = 250_000, outputTokens = 1_000)) // $1.00 + $0.02
            assertEquals(MicroUsd(1_020_000), g.snapshot.value.sessionSpent)
            assertTrue(g.snapshot.value.exhausted)
        }

    @Test
    fun newSessionResetsSessionButNotRolling() =
        runBlocking {
            val g = guard()
            g.settle(approved(g.reserve(opus, 0, outTokensForCents(100))), opus, null)
            g.startNewSession()
            val s = g.snapshot.value
            assertEquals(MicroUsd.ZERO, s.sessionSpent)
            assertEquals(MicroUsd.ofUsd(1), s.rollingSpent)
            assertFalse(s.exhausted)
            assertTrue(g.reserve(opus, 0, 1) is SpendDecision.Approved)
        }

    @Test
    fun reservationFromOldSessionChargesRollingOnly() =
        runBlocking {
            val g = guard()
            val r = approved(g.reserve(opus, 0, outTokensForCents(50)))
            g.startNewSession()
            g.settle(r, opus, null)
            assertEquals(MicroUsd.ZERO, g.snapshot.value.sessionSpent)
            assertEquals(MicroUsd.ofCents(50), g.snapshot.value.rollingSpent)
        }

    @Test
    fun rollingCapAcrossSessions() =
        runBlocking {
            val g = guard()
            g.setCaps(MicroUsd.ofUsd(50), MicroUsd.ofUsd(2))
            g.settle(approved(g.reserve(opus, 0, outTokensForCents(150))), opus, null)
            g.startNewSession()
            val d = g.reserve(opus, 0, outTokensForCents(51))
            assertEquals(Reason.RollingCap, (d as SpendDecision.Denied).reason)
            assertTrue(g.reserve(opus, 0, outTokensForCents(50)) is SpendDecision.Approved)
        }

    @Test
    fun rollingCapOff() =
        runBlocking {
            val g = guard()
            g.setCaps(MicroUsd.ofUsd(50), null)
            assertNull(g.snapshot.value.rollingCap)
            repeat(3) {
                g.settle(approved(g.reserve(opus, 0, outTokensForCents(1_000))), opus, null)
                g.beginInstruction()
            }
            assertEquals(MicroUsd.ofUsd(30), g.snapshot.value.rollingSpent)
            assertFalse(g.snapshot.value.exhausted)
        }

    @Test
    fun rollingWindowExpiry() =
        runBlocking {
            val g = guard()
            g.setCaps(MicroUsd.ofUsd(50), MicroUsd.ofUsd(1))
            g.settle(approved(g.reserve(opus, 0, outTokensForCents(100))), opus, null)
            assertTrue(g.snapshot.value.exhausted)

            clock.advance(Duration.ofDays(29))
            g.refresh()
            assertEquals(MicroUsd.ofUsd(1), g.snapshot.value.rollingSpent)
            assertEquals(Reason.RollingCap, (g.reserve(opus, 0, 1) as SpendDecision.Denied).reason)

            // Charge made at an hour boundary: still counted one ms before 30 days + 1 h...
            clock.nowMs = start + Duration.ofDays(30).toMillis() - 1
            g.refresh()
            assertEquals(MicroUsd.ofUsd(1), g.snapshot.value.rollingSpent)

            // ...and gone once the whole hour bucket is older than 30 days.
            clock.nowMs = start + Duration.ofDays(30).toMillis() + Duration.ofHours(1).toMillis()
            g.refresh()
            assertEquals(MicroUsd.ZERO, g.snapshot.value.rollingSpent)
            assertTrue(store.saved!!.hourlyCharges.isEmpty(), "expired buckets are pruned from the record")
            assertTrue(g.reserve(opus, 0, 1) is SpendDecision.Approved)
        }

    @Test
    fun sessionEndsAfter12HoursIdle() =
        runBlocking {
            val g = guard()
            g.settle(approved(g.reserve(opus, 0, outTokensForCents(40))), opus, null)
            clock.advance(Duration.ofHours(12).minusMillis(1))
            g.refresh()
            assertEquals(MicroUsd.ofCents(40), g.snapshot.value.sessionSpent)
            clock.advance(Duration.ofMillis(1))
            g.refresh()
            assertEquals(MicroUsd.ZERO, g.snapshot.value.sessionSpent)
            assertEquals(MicroUsd.ofCents(40), g.snapshot.value.rollingSpent)
        }

    @Test
    fun loopLimitTwelveRoundTripsPerInstruction() =
        runBlocking {
            val g = guard()
            repeat(12) { g.settle(approved(g.reserve(opus, 0, 1)), opus, Usage(0, 1)) }
            assertEquals(Reason.LoopLimit, (g.reserve(opus, 0, 1) as SpendDecision.Denied).reason)
            g.beginInstruction()
            assertTrue(g.reserve(opus, 0, 1) is SpendDecision.Approved)
        }

    @Test
    fun releasedRequestDoesNotCountTowardLoopLimit() =
        runBlocking {
            val g = guard()
            repeat(20) { g.release(approved(g.reserve(opus, 0, 1))) }
            assertTrue(g.reserve(opus, 0, 1) is SpendDecision.Approved)
        }

    @Test
    fun capRangesValidated() =
        runBlocking {
            val g = guard()
            g.setCaps(MicroUsd.ofCents(10), MicroUsd.ofUsd(1))
            g.setCaps(MicroUsd.ofUsd(50), MicroUsd.ofUsd(500))
            assertThrows<IllegalArgumentException> { runBlocking { g.setCaps(MicroUsd(99_999), null) } }
            assertThrows<IllegalArgumentException> { runBlocking { g.setCaps(MicroUsd.ofUsd(51), null) } }
            assertThrows<IllegalArgumentException> { runBlocking { g.setCaps(MicroUsd.ofUsd(1), MicroUsd(999_999)) } }
            assertThrows<IllegalArgumentException> { runBlocking { g.setCaps(MicroUsd.ofUsd(1), MicroUsd.ofUsd(501)) } }
        }

    @Test
    fun loweringCapBelowSpentExhausts() =
        runBlocking {
            val g = guard()
            g.settle(approved(g.reserve(opus, 0, outTokensForCents(50))), opus, null)
            g.setCaps(MicroUsd.ofCents(20), MicroUsd.ofUsd(20))
            assertTrue(g.snapshot.value.exhausted)
            assertEquals(Reason.SessionCap, (g.reserve(opus, 0, 0) as SpendDecision.Denied).reason)
        }

    @Test
    fun concurrentReservationsNeverExceedCap() =
        runBlocking {
            // Loop limit lifted so only the cap decides.
            val g = guard(maxRoundTrips = Int.MAX_VALUE)
            // 200 concurrent reservations of $0.03 against a $1.00 cap: at most 33 can be approved.
            val decisions =
                (1..200)
                    .map { async(Dispatchers.Default) { g.reserve(opus, 0, outTokensForCents(3)) } }
                    .awaitAll()
            val approvedCount = decisions.count { it is SpendDecision.Approved }
            assertEquals(33, approvedCount)
            assertEquals(MicroUsd.ofCents(99), g.snapshot.value.reserved)
            assertTrue(g.snapshot.value.reserved <= g.snapshot.value.sessionCap)
            assertTrue(decisions.filterIsInstance<SpendDecision.Denied>().all { it.reason == Reason.SessionCap })
        }

    @Test
    fun concurrentSettleAndReserveKeepSpentPlusReservedWithinCap() =
        runBlocking {
            val g = guard(maxRoundTrips = Int.MAX_VALUE)
            g.setCaps(MicroUsd.ofUsd(50), null)
            val maxSeen =
                java.util.concurrent.atomic
                    .AtomicLong()
            (1..100)
                .map {
                    async(Dispatchers.Default) {
                        val d = g.reserve(opus, 0, outTokensForCents(100))
                        val s = g.snapshot.value
                        maxSeen.accumulateAndGet(s.sessionSpent.value + s.reserved.value, ::maxOf)
                        if (d is SpendDecision.Approved) g.settle(d.reservation, opus, null)
                        g.beginInstruction()
                    }
                }.awaitAll()
            assertEquals(MicroUsd.ofUsd(50), g.snapshot.value.sessionSpent)
            assertTrue(maxSeen.get() <= MicroUsd.ofUsd(50).value)
        }

    @Test
    fun persistsAcrossReopenAndChargesOrphanedReservations() =
        runBlocking {
            val g1 = guard()
            g1.settle(approved(g1.reserve(opus, 0, outTokensForCents(10))), opus, null)
            approved(g1.reserve(opus, 0, outTokensForCents(30))) // process "dies" with this pending
            g1.setCaps(MicroUsd.ofUsd(5), MicroUsd.ofUsd(100))

            val g2 = guard()
            val s = g2.snapshot.value
            assertEquals(MicroUsd.ofCents(40), s.sessionSpent)
            assertEquals(MicroUsd.ofCents(40), s.rollingSpent)
            assertEquals(MicroUsd.ZERO, s.reserved)
            assertEquals(MicroUsd.ofUsd(5), s.sessionCap)
            assertEquals(MicroUsd.ofUsd(100), s.rollingCap)
        }

    @Test
    fun codecRoundTripAndCorruptRecord() =
        runBlocking {
            val g = guard()
            g.settle(approved(g.reserve(opus, 0, 1_000)), opus, null)
            approved(g.reserve(opus, 0, 500))
            val state = store.saved!!
            assertEquals(state, SpendLedgerCodec.decode(SpendLedgerCodec.encode(state)))
            assertNull(SpendLedgerCodec.decode("not json"))
            assertNull(SpendLedgerCodec.decode(SpendLedgerCodec.encode(state.copy(version = 99))))
        }

    @Test
    fun reserveFailsClosedWhenPersistenceFails() =
        runBlocking {
            val failing =
                object : SpendLedgerStore {
                    var fail = false

                    override suspend fun load(): SpendLedgerState? = null

                    override suspend fun save(state: SpendLedgerState) {
                        if (fail) throw IOException("disk full")
                    }
                }
            val g = guard(failing)
            failing.fail = true
            assertThrows<IOException> { runBlocking { g.reserve(opus, 0, 10) } }
            assertEquals(MicroUsd.ZERO, g.snapshot.value.reserved)
        }

    @Test
    fun snapshotRemaining() {
        val s = SpendSnapshot(MicroUsd(300), MicroUsd(1_000), MicroUsd(900), MicroUsd(1_500), MicroUsd(100), false)
        assertEquals(MicroUsd(500), s.remaining())
        assertEquals(MicroUsd(600), s.copy(rollingCap = null).remaining())
        assertEquals(MicroUsd.ZERO, s.copy(sessionSpent = MicroUsd(5_000)).remaining())
    }
}
