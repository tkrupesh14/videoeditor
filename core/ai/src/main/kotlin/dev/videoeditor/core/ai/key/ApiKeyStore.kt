package dev.videoeditor.core.ai.key

import kotlinx.coroutines.flow.StateFlow

/** Observable key state for the settings UI and for gating AI entry points. Never carries the key. */
sealed interface ApiKeyState {
    /** [hint] is the last 4 characters of the key. */
    data class Present(
        val hint: String,
        val savedAtEpochMs: Long,
    ) : ApiKeyState

    data class Missing(
        val reason: Reason,
    ) : ApiKeyState {
        enum class Reason { NeverSet, Removed, Invalidated }
    }

    /** Transient storage failure; data is kept and AI is disabled until the next successful read. */
    data object Unavailable : ApiKeyState
}

sealed interface SaveResult {
    data object Saved : SaveResult

    data class Rejected(
        val reason: ApiKeyFormat.Problem,
    ) : SaveResult

    data object StorageFailed : SaveResult
}

/** Storage for the user's Anthropic Console API key (docs/design/security.md, A1.4). */
interface ApiKeyStore {
    /** Observable for settings UI and for gating AI entry points. Never carries the key. */
    val state: StateFlow<ApiKeyState>

    /** Validates format (A2.4), encrypts, persists. Returns the format failure instead of throwing. */
    suspend fun save(key: SecretString): SaveResult

    /** Decrypts for one request. Null when state is not Present. Caller must close() the result. */
    suspend fun load(): SecretString?

    /** Deletes ciphertext, hint and the keystore alias. Idempotent. */
    suspend fun clear()
}

/** User-facing text for [ApiKeyState.Missing.Reason.Invalidated] (A1.3). */
const val API_KEY_INVALIDATED_MESSAGE = "Your saved API key could not be read on this device. Enter it again."

/** The disclosure sentence required by A4, unshortened. */
const val API_CREDITS_DISCLOSURE = "Uses your Anthropic API credits, not your Claude subscription."
