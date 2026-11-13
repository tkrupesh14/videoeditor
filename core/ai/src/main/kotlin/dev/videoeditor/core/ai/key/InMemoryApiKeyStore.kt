package dev.videoeditor.core.ai.key

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * Process-memory [ApiKeyStore] for tests and for the headless `:core:mcp` process, which never needs a key.
 * Same validation and state transitions as the Android Keystore store, without persistence.
 */
class InMemoryApiKeyStore(
    private val clock: Clock = Clock.systemUTC(),
) : ApiKeyStore {
    private val mutex = Mutex()
    private var stored: SecretString? = null
    private val mutableState = MutableStateFlow<ApiKeyState>(ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet))

    override val state: StateFlow<ApiKeyState> = mutableState.asStateFlow()

    override suspend fun save(key: SecretString): SaveResult =
        mutex.withLock {
            val problem = key.use { ApiKeyFormat.check(it) }
            if (problem != null) return@withLock SaveResult.Rejected(problem)
            val (copy, hint) = key.use { SecretString.of(ApiKeyFormat.normalized(it)) to ApiKeyFormat.hint(it) }
            stored?.close()
            stored = copy
            mutableState.value = ApiKeyState.Present(hint, clock.millis())
            SaveResult.Saved
        }

    override suspend fun load(): SecretString? =
        mutex.withLock {
            stored?.use { SecretString.of(it) }
        }

    override suspend fun clear() {
        mutex.withLock {
            val had = stored != null
            stored?.close()
            stored = null
            if (had) mutableState.value = ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed)
        }
    }
}
