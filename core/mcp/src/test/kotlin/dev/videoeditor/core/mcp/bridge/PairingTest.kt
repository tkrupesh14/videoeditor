package dev.videoeditor.core.mcp.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class PairingTest {
    private val clock = AtomicLong(1_000L)
    private val store = InMemoryClientStore("b_test")

    private fun manager(limits: PairingLimits = PairingLimits(wrongCodeDelayMs = 0, approvalTimeoutMs = 200)) =
        DefaultPairingManager(store, clock = { clock.get() }, random = SecureRandom(), limits = limits)

    private fun wrong(code: String) = if (code == "00000000") "11111111" else "00000000"

    @Test
    fun codeIsEightDigits() {
        val r = SecureRandom()
        repeat(200) { assertTrue(Regex("^\\d{8}$").matches(BridgeCrypto.newPairingCode(r))) }
    }

    @Test
    fun fiveWrongCodesBurnTheCode() =
        runBlocking {
            val m = manager()
            val code = m.beginPairing().code
            repeat(5) { assertEquals(RedeemResult.WrongCode, m.redeem(wrong(code), "x")) }
            // The right code no longer works: it was burned.
            assertEquals(RedeemResult.NotOffering, m.redeem(code, "x"))
            assertTrue(store.list().isEmpty())
        }

    @Test
    fun threeBurnedCodesLockPairingForTenMinutes() =
        runBlocking {
            val m = manager()
            repeat(3) {
                val code = m.beginPairing().code
                repeat(5) { m.redeem(wrong(code), "x") }
            }
            assertInstanceOf(PairingState.LockedOut::class.java, m.pairing.value)
            val code = m.beginPairing().code
            assertEquals("", code) // no usable code while locked
            assertInstanceOf(RedeemResult.LockedOut::class.java, m.redeem("12345678", "x"))
            clock.addAndGet(10 * 60_000L + 1)
            val fresh = m.beginPairing().code
            assertEquals(8, fresh.length)
        }

    @Test
    fun expiredCodeFails() =
        runBlocking {
            val m = manager()
            val code = m.beginPairing().code
            clock.addAndGet(120_001)
            assertEquals(RedeemResult.NotOffering, m.redeem(code, "x"))
        }

    @Test
    fun correctCodeWithoutAllowYieldsNoToken() =
        runBlocking {
            val m = manager()
            val code = m.beginPairing().code
            assertEquals(RedeemResult.Denied, m.redeem(code, "x")) // nobody tapped Allow within the timeout
            assertTrue(store.list().isEmpty())
        }

    @Test
    fun denyYieldsNoTokenAndCodeIsSingleUse() =
        runBlocking {
            val m = manager(PairingLimits(wrongCodeDelayMs = 0, approvalTimeoutMs = 5_000))
            val code = m.beginPairing().code
            val r =
                async {
                    m.redeem(code, "x")
                }
            while (m.pairing.value !is PairingState.AwaitingApproval) kotlinx.coroutines.delay(1)
            m.answer((m.pairing.value as PairingState.AwaitingApproval).requestId, false, BridgeScope.DEFAULT)
            assertEquals(RedeemResult.Denied, r.await())
            assertEquals(RedeemResult.NotOffering, m.redeem(code, "x")) // reused code
        }

    @Test
    fun allowCreatesClientWithVerifierOnly() =
        runBlocking {
            val m = manager(PairingLimits(wrongCodeDelayMs = 0, approvalTimeoutMs = 5_000))
            val code = m.beginPairing().code
            val r = async { m.redeem(code, "claude-code@box") }
            while (m.pairing.value !is PairingState.AwaitingApproval) kotlinx.coroutines.delay(1)
            m.answer((m.pairing.value as PairingState.AwaitingApproval).requestId, true, setOf(BridgeScope.READ))
            val paired = r.await() as RedeemResult.Paired
            val c = paired.credentials
            assertTrue(c.token.use { it.startsWith("vedb1_") && it.length >= 6 + 43 })
            assertEquals(setOf(BridgeScope.READ), c.scopes)
            assertEquals(
                "claude-code@box",
                m.clients.value
                    .single()
                    .name,
            )
            // Stored: only the verifier, which equals SHA-256 of the token, not the token.
            val stored = store.verifier(c.clientId)!!
            assertTrue(BridgeCrypto.constantTimeEquals(stored, BridgeCrypto.verifier(c.token)))
            assertFalse(c.toString().contains(c.token.use { it }))
        }

    @Test
    fun maxEightClients() =
        runBlocking {
            repeat(8) { store.add(PairedClient("c_$it", "n", BridgeScope.DEFAULT, 0, null), ByteArray(32)) }
            val m = manager()
            val code = m.beginPairing().code
            assertEquals(RedeemResult.TooManyClients, m.redeem(code, "x"))
        }
}
