package dev.videoeditor.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The only code that touches the real AndroidKeyStore (A1.2). Kept thin on purpose: it is not covered by
 * JVM tests and needs an on-device (instrumented) test.
 */
class AndroidKeystoreAesGcmCipher(
    private val alias: String = ALIAS,
) : ApiKeyCipher {
    override fun encrypt(plaintext: ByteArray): ApiKeyCipher.Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        cipher.updateAAD(AAD)
        val out = cipher.doFinal(plaintext)
        return ApiKeyCipher.Sealed(cipher.iv, out)
    }

    override fun decrypt(sealed: ApiKeyCipher.Sealed): ByteArray {
        val key = existingKey() ?: throw KeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed.ciphertextAndTag)
    }

    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // Fall through to the TEE-backed key.
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "dev.videoeditor.apikey.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private val AAD = "dev.videoeditor.apikey|v1".toByteArray(Charsets.UTF_8)
    }
}
