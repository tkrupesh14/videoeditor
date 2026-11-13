package dev.videoeditor.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** HashMap-backed [Prefs]; [failCommits] simulates a failed disk write. */
class FakePrefs : Prefs {
    val values = HashMap<String, Any>()
    var failCommits = false

    override fun getString(key: String): String? = values[key] as String?

    override fun getLong(key: String): Long? = values[key] as Long?

    override fun commit(
        puts: Map<String, Any>,
        removes: Collection<String>,
    ): Boolean {
        if (failCommits) return false
        removes.forEach { values.remove(it) }
        values.putAll(puts)
        return true
    }
}

/**
 * Software AES-256-GCM with the same IV/tag/AAD shape as [AndroidKeystoreAesGcmCipher], plus hooks to inject
 * the failures the Keystore can produce.
 */
class FakeCipher : ApiKeyCipher {
    private var key: SecretKey? = null
    var deleteCount = 0
    var encryptFailure: (() -> Exception)? = null
    var decryptFailure: (() -> Exception)? = null

    override fun encrypt(plaintext: ByteArray): ApiKeyCipher.Sealed {
        encryptFailure?.let {
            encryptFailure = null
            throw it()
        }
        val k =
            key ?: KeyGenerator
                .getInstance("AES")
                .apply { init(256) }
                .generateKey()
                .also { key = it }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        c.updateAAD(AAD)
        return ApiKeyCipher.Sealed(iv, c.doFinal(plaintext))
    }

    override fun decrypt(sealed: ApiKeyCipher.Sealed): ByteArray {
        decryptFailure?.let { throw it() }
        val k = key ?: throw KeyMissingException()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed.iv))
        c.updateAAD(AAD)
        return c.doFinal(sealed.ciphertextAndTag)
    }

    override fun deleteKey() {
        deleteCount++
        key = null
    }

    private companion object {
        val AAD = "dev.videoeditor.apikey|v1".toByteArray()
    }
}

object AppFixtures {
    /** Obviously fake key, assembled so no source line holds a key-shaped literal. */
    fun fakeKey(): String = "sk-" + "ant-" + "api03-FAKEappTEST" + "Q".repeat(60)
}
