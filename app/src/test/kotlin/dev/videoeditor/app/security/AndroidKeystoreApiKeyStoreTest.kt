package dev.videoeditor.app.security

import dev.videoeditor.core.ai.key.ApiKeyFormat
import dev.videoeditor.core.ai.key.ApiKeyState
import dev.videoeditor.core.ai.key.SaveResult
import dev.videoeditor.core.ai.key.SecretString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class AndroidKeystoreApiKeyStoreTest {
    private val prefs = FakePrefs()
    private val cipher = FakeCipher()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    private val key = AppFixtures.fakeKey()

    private fun store() = AndroidKeystoreApiKeyStore(prefs, cipher, clock, Dispatchers.Unconfined)

    private fun AndroidKeystoreApiKeyStore.loadText(): String? = runBlocking { load() }?.use { it.toString() }

    @Test
    fun saveLoadRoundTripAndRecordFormat() =
        runBlocking {
            val s = store()
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet), s.state.value)
            assertEquals(SaveResult.Saved, s.save(SecretString.of("  $key\n")))
            assertEquals(ApiKeyState.Present(key.takeLast(4), clock.millis()), s.state.value)
            assertEquals(key, s.loadText())

            val record = prefs.getString(AndroidKeystoreApiKeyStore.KEY_RECORD)!!
            assertFalse(record.contains(key))
            val raw = Base64.getDecoder().decode(record)
            assertEquals(0x01.toByte(), raw[0])
            assertEquals(1 + 12 + key.length + 16, raw.size)
            assertEquals(key.takeLast(4), prefs.getString(AndroidKeystoreApiKeyStore.KEY_HINT))
            assertEquals(clock.millis(), prefs.getLong(AndroidKeystoreApiKeyStore.KEY_SAVED_AT))
            assertFalse(prefs.values.values.any { it.toString().contains(key) })
        }

    @Test
    fun eachSaveUsesFreshIv() =
        runBlocking {
            val s = store()
            s.save(SecretString.of(key))
            val a = ApiKeyRecord.decode(prefs.getString(AndroidKeystoreApiKeyStore.KEY_RECORD)!!).iv
            s.save(SecretString.of(key))
            val b = ApiKeyRecord.decode(prefs.getString(AndroidKeystoreApiKeyStore.KEY_RECORD)!!).iv
            assertFalse(a.contentEquals(b))
        }

    @Test
    fun formatRejectionStoresNothing() =
        runBlocking {
            val s = store()
            val oat = SecretString.of("sk-" + "ant-oat01-" + "x".repeat(60))
            assertEquals(SaveResult.Rejected(ApiKeyFormat.Problem.SubscriptionToken), s.save(oat))
            assertTrue(prefs.values.isEmpty())
            assertNull(s.load())
        }

    @Test
    fun stateRestoredFromPrefsWithoutTouchingKeystore() =
        runBlocking {
            store().save(SecretString.of(key))
            cipher.decryptFailure = { error("must not decrypt on construction") }
            val s = store()
            assertEquals(ApiKeyState.Present(key.takeLast(4), clock.millis()), s.state.value)
        }

    @Test
    fun tamperedCiphertextIsWipedAsInvalidated() = assertWipedAfter { corruptLastByte() }

    @Test
    fun badVersionByteIsWipedAsInvalidated() =
        assertWipedAfter {
            val raw = Base64.getDecoder().decode(prefs.getString(AndroidKeystoreApiKeyStore.KEY_RECORD)!!)
            raw[0] = 0x02
            prefs.values[AndroidKeystoreApiKeyStore.KEY_RECORD] = Base64.getEncoder().encodeToString(raw)
        }

    @Test
    fun missingAliasIsWipedAsInvalidated() = assertWipedAfter { cipher.deleteKey() }

    @Test
    fun keystoreExceptionsWrappedInProviderExceptionAreInvalidated() =
        assertWipedAfter { cipher.decryptFailure = { ProviderException(UnrecoverableKeyException("gone")) } }

    @Test
    fun transientFailureKeepsDataAndRecovers() =
        runBlocking {
            val s = store()
            s.save(SecretString.of(key))
            cipher.decryptFailure = { ProviderException("keystore busy") }
            assertNull(s.load())
            assertEquals(ApiKeyState.Unavailable, s.state.value)
            assertTrue(prefs.values.containsKey(AndroidKeystoreApiKeyStore.KEY_RECORD))
            assertEquals(0, cipher.deleteCount)

            cipher.decryptFailure = null
            assertEquals(key, s.loadText())
            assertEquals(ApiKeyState.Present(key.takeLast(4), clock.millis()), s.state.value)
        }

    @Test
    fun invalidatedKeyOnEncryptIsRegeneratedOnce() =
        runBlocking {
            val s = store()
            cipher.encryptFailure = { InvalidKeyException("KeyPermanentlyInvalidated") }
            assertEquals(SaveResult.Saved, s.save(SecretString.of(key)))
            assertEquals(1, cipher.deleteCount)
            assertEquals(key, s.loadText())
        }

    @Test
    fun transientEncryptFailureAndFailedWriteReportStorageFailed() =
        runBlocking {
            val s = store()
            cipher.encryptFailure = { ProviderException("busy") }
            assertEquals(SaveResult.StorageFailed, s.save(SecretString.of(key)))
            prefs.failCommits = true
            assertEquals(SaveResult.StorageFailed, s.save(SecretString.of(key)))
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet), s.state.value)
            assertTrue(prefs.values.isEmpty())
        }

    @Test
    fun clearDeletesEverythingAndIsIdempotent() =
        runBlocking {
            val s = store()
            s.clear()
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet), s.state.value)
            s.save(SecretString.of(key))
            s.clear()
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed), s.state.value)
            assertTrue(prefs.values.isEmpty())
            assertTrue(cipher.deleteCount >= 1)
            s.clear()
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed), s.state.value)
            assertNull(s.load())
        }

    @Test
    fun utf8HelpersRoundTripWithoutLeakingInToString() {
        val bytes = AndroidKeystoreApiKeyStore.encodeUtf8(key)
        assertArrayEquals(key.toByteArray(), bytes)
        val secret = AndroidKeystoreApiKeyStore.decodeUtf8(bytes)
        assertFalse(secret.toString().contains(key))
        assertEquals(key, secret.use { it.toString() })
    }

    private fun corruptLastByte() {
        val raw = Base64.getDecoder().decode(prefs.getString(AndroidKeystoreApiKeyStore.KEY_RECORD)!!)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        prefs.values[AndroidKeystoreApiKeyStore.KEY_RECORD] = Base64.getEncoder().encodeToString(raw)
    }

    private fun assertWipedAfter(damage: () -> Unit) =
        runBlocking {
            val s = store()
            s.save(SecretString.of(key))
            damage()
            assertNull(s.load())
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.Invalidated), s.state.value)
            assertTrue(prefs.values.isEmpty())
            assertTrue(cipher.deleteCount >= 1)
            // No retry loop: a second load stays Missing without touching the cipher again.
            val deletes = cipher.deleteCount
            assertNull(s.load())
            assertEquals(deletes, cipher.deleteCount)
        }
}
