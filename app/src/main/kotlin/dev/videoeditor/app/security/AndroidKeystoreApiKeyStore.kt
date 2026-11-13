package dev.videoeditor.app.security

import android.content.Context
import dev.videoeditor.core.ai.key.ApiKeyFormat
import dev.videoeditor.core.ai.key.ApiKeyState
import dev.videoeditor.core.ai.key.ApiKeyStore
import dev.videoeditor.core.ai.key.SaveResult
import dev.videoeditor.core.ai.key.SecretString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock

/**
 * [ApiKeyStore] backed by an AndroidKeyStore AES-256-GCM key (docs/design/security.md, A1.2 and A1.3).
 * The ciphertext record, the display hint and the save time live in the private prefs file `secure_ai.xml`,
 * which is excluded from backup and device transfer.
 *
 * Failure policy: a permanently unusable key or record is wiped and reported as `Missing(Invalidated)`;
 * anything else is `Unavailable` and the data is kept. Exceptions never escape and never carry the key.
 */
class AndroidKeystoreApiKeyStore(
    private val prefs: Prefs,
    private val cipher: ApiKeyCipher,
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ApiKeyStore {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(initialState())

    override val state: StateFlow<ApiKeyState> = mutableState.asStateFlow()

    override suspend fun save(key: SecretString): SaveResult {
        val problem = key.use { ApiKeyFormat.check(it) }
        if (problem != null) return SaveResult.Rejected(problem)
        return withContext(io) {
            mutex.withLock {
                val hint = key.use { ApiKeyFormat.hint(it) }
                val plaintext = key.use { encodeUtf8(ApiKeyFormat.normalized(it)) }
                try {
                    val record = ApiKeyRecord.encode(encryptRecoveringFromDeadKey(plaintext))
                    val ok =
                        prefs.commit(
                            puts = mapOf(KEY_RECORD to record, KEY_HINT to hint, KEY_SAVED_AT to clock.millis()),
                        )
                    if (!ok) return@withLock SaveResult.StorageFailed
                    mutableState.value = ApiKeyState.Present(hint, prefs.getLong(KEY_SAVED_AT) ?: clock.millis())
                    SaveResult.Saved
                } catch (e: Exception) {
                    SaveResult.StorageFailed
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }

    override suspend fun load(): SecretString? =
        withContext(io) {
            mutex.withLock {
                val text = prefs.getString(KEY_RECORD) ?: return@withLock null
                var plaintext: ByteArray? = null
                try {
                    plaintext = cipher.decrypt(ApiKeyRecord.decode(text))
                    val secret = decodeUtf8(plaintext)
                    if (mutableState.value !is ApiKeyState.Present) mutableState.value = presentFromPrefs()
                    secret
                } catch (e: Exception) {
                    when (classifyKeyFailure(e)) {
                        KeyFailure.Invalidated -> {
                            wipe()
                            mutableState.value = ApiKeyState.Missing(ApiKeyState.Missing.Reason.Invalidated)
                        }
                        KeyFailure.Transient -> mutableState.value = ApiKeyState.Unavailable
                    }
                    null
                } finally {
                    plaintext?.fill(0)
                }
            }
        }

    override suspend fun clear() {
        withContext(io) {
            mutex.withLock {
                val had = prefs.getString(KEY_RECORD) != null || mutableState.value !is ApiKeyState.Missing
                wipe()
                if (had) mutableState.value = ApiKeyState.Missing(ApiKeyState.Missing.Reason.Removed)
            }
        }
    }

    /** One retry after deleting the alias when the existing key is permanently unusable (A1.3). */
    private fun encryptRecoveringFromDeadKey(plaintext: ByteArray): ApiKeyCipher.Sealed =
        try {
            cipher.encrypt(plaintext)
        } catch (e: Exception) {
            if (classifyKeyFailure(e) != KeyFailure.Invalidated) throw e
            cipher.deleteKey()
            cipher.encrypt(plaintext)
        }

    /** Best effort: deletes ciphertext, hint, save time and the Keystore alias. */
    private fun wipe() {
        prefs.commit(removes = listOf(KEY_RECORD, KEY_HINT, KEY_SAVED_AT))
        try {
            cipher.deleteKey()
        } catch (e: Exception) {
            // Alias stays; the next save replaces or regenerates it.
        }
    }

    /** From prefs only, so construction never touches the Keystore; [load] detects a dead key. */
    private fun initialState(): ApiKeyState =
        if (prefs.getString(KEY_RECORD) == null) {
            ApiKeyState.Missing(ApiKeyState.Missing.Reason.NeverSet)
        } else {
            presentFromPrefs()
        }

    private fun presentFromPrefs(): ApiKeyState.Present =
        ApiKeyState.Present(prefs.getString(KEY_HINT).orEmpty(), prefs.getLong(KEY_SAVED_AT) ?: 0L)

    companion object {
        /** Prefs file name without `.xml`; must match res/xml backup exclusions. */
        const val PREFS_FILE = "secure_ai"
        const val KEY_RECORD = "anthropic_api_key"
        const val KEY_HINT = "key_hint"
        const val KEY_SAVED_AT = "saved_at"

        fun create(context: Context): AndroidKeystoreApiKeyStore =
            AndroidKeystoreApiKeyStore(
                prefs = SharedPreferencesPrefs.open(context.applicationContext, PREFS_FILE),
                cipher = AndroidKeystoreAesGcmCipher(),
            )

        /** UTF-8 bytes without an intermediate String. Caller zeroes the result. */
        internal fun encodeUtf8(chars: CharSequence): ByteArray {
            val encoder =
                Charsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buf = encoder.encode(CharBuffer.wrap(chars))
            val out = ByteArray(buf.remaining())
            buf.get(out)
            if (buf.hasArray()) buf.array().fill(0)
            return out
        }

        /** Decodes into a [SecretString] without an intermediate String; zeroes the temporary chars. */
        internal fun decodeUtf8(bytes: ByteArray): SecretString {
            val decoder =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val chars = decoder.decode(ByteBuffer.wrap(bytes))
            return try {
                SecretString.of(chars)
            } finally {
                if (chars.hasArray()) chars.array().fill('\u0000')
            }
        }
    }
}
