package dev.videoeditor.app.security

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * AES-256-GCM over one Keystore-held key (docs/design/security.md, A1.2). The production implementation is
 * [AndroidKeystoreAesGcmCipher]; JVM tests substitute a fake so the store logic is testable off-device.
 */
interface ApiKeyCipher {
    /** Encrypts with a fresh Keystore-generated IV, creating the key on first use. */
    fun encrypt(plaintext: ByteArray): Sealed

    /** @throws KeyMissingException when the alias is gone; other failures propagate as thrown. */
    fun decrypt(sealed: Sealed): ByteArray

    /** Deletes the Keystore alias. Idempotent. */
    fun deleteKey()

    class Sealed(
        val iv: ByteArray,
        /** Ciphertext followed by the 16-byte GCM tag, as `Cipher.doFinal` returns it. */
        val ciphertextAndTag: ByteArray,
    )
}

/** The alias is absent although a ciphertext exists (key wiped by restore or OEM reset). */
class KeyMissingException : GeneralSecurityException("Keystore key is missing")

/** The stored record is malformed (bad version byte, short blob, not base64). */
class CorruptRecordException(
    message: String,
) : GeneralSecurityException(message)

/** How a storage failure is handled (A1.3). */
enum class KeyFailure {
    /** Key or ciphertext permanently unusable: wipe and ask again. */
    Invalidated,

    /** Possibly temporary: keep data, report Unavailable. */
    Transient,
}

/**
 * Classifies by walking the cause chain, so a `ProviderException` wrapping one of the permanent causes is
 * still treated as permanent. `KeyPermanentlyInvalidatedException` is an [InvalidKeyException].
 */
fun classifyKeyFailure(error: Throwable): KeyFailure {
    var t: Throwable? = error
    val seen = HashSet<Throwable>()
    while (t != null && seen.add(t)) {
        when (t) {
            is AEADBadTagException,
            is InvalidKeyException,
            is UnrecoverableKeyException,
            is KeyStoreException,
            is KeyMissingException,
            is CorruptRecordException,
            -> return KeyFailure.Invalidated
        }
        t = t.cause
    }
    return KeyFailure.Transient
}
