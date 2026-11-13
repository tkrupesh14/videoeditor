package dev.videoeditor.core.ai.key

import dev.videoeditor.core.ai.key.Fixtures.CANARY
import dev.videoeditor.core.ai.key.Fixtures.fakeKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InMemoryApiKeyStoreTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)

    @Test
    fun saveLoadClear() =
        runBlocking {
            val store = InMemoryApiKeyStore(clock)
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet), store.state.value)
            assertNull(store.load())
            val key = fakeKey(60).dropLast(4) + "wxyz"
            assertEquals(SaveResult.Saved, store.save(SecretString.of(" $key\n")))
            assertEquals(ApiKeyState.Present("wxyz", 1_700_000_000_000), store.state.value)
            val loaded = store.load()!!
            assertEquals(key, loaded.use { it.toString() })
            loaded.close()
            // A closed copy does not affect the stored key.
            assertEquals(key, store.load()!!.use { it.toString() })
            store.clear()
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed), store.state.value)
            assertNull(store.load())
            store.clear() // idempotent
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed), store.state.value)
        }

    @Test
    fun rejectsSubscriptionTokenWithoutStoring() =
        runBlocking {
            val store = InMemoryApiKeyStore(clock)
            val result = store.save(SecretString.of("sk-ant-" + "oat01-" + "A".repeat(80)))
            assertEquals(SaveResult.Rejected(ApiKeyFormat.Problem.SubscriptionToken), result)
            assertNull(store.load())
            assertEquals(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet), store.state.value)
        }

    @Test
    fun stateAndResultsNeverCarryTheKey() =
        runBlocking {
            val store = InMemoryApiKeyStore(clock)
            val result = store.save(SecretString.of(CANARY))
            listOf(result.toString(), store.state.value.toString(), store.load().toString()).forEach {
                assertFalse(it.contains("CANARY"), it)
            }
        }
}
