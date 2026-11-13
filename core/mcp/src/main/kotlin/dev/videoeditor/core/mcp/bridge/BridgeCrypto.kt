package dev.videoeditor.core.mcp.bridge

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Token, id, verifier and proof formats (docs/design/security.md B4, B5). */
object BridgeCrypto {
    const val TOKEN_PREFIX = "vedb1_"
    private const val VERIFIER_LABEL = "vedit-bridge-verifier-v1"
    private const val AUTH_LABEL = "vedit-bridge-auth-v1"
    private const val SERVER_LABEL = "vedit-bridge-server-v1"
    const val NONCE_BYTES = 32

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun b64url(bytes: ByteArray): String = b64.encodeToString(bytes)

    /** Null when [s] is not valid base64url. */
    fun unb64url(s: String): ByteArray? =
        try {
            b64d.decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }

    fun randomBytes(
        n: Int,
        random: SecureRandom,
    ): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newToken(random: SecureRandom): SecretToken = SecretToken.of(TOKEN_PREFIX + b64url(randomBytes(32, random)))

    fun newClientId(random: SecureRandom): String = "c_" + b64url(randomBytes(16, random))

    fun newBridgeId(random: SecureRandom): String = "b_" + b64url(randomBytes(16, random))

    /** Uniform 8-digit code by rejection sampling. */
    fun newPairingCode(random: SecureRandom): String {
        val limit = (Int.MAX_VALUE / 100_000_000) * 100_000_000
        while (true) {
            val v = random.nextInt() and Int.MAX_VALUE
            if (v < limit) return (v % 100_000_000).toString().padStart(8, '0')
        }
    }

    /** SHA-256("vedit-bridge-verifier-v1" || 0x00 || token). */
    fun verifier(token: SecretToken): ByteArray =
        token.use { t ->
            val md = MessageDigest.getInstance("SHA-256")
            md.update(VERIFIER_LABEL.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.digest(t.toByteArray(Charsets.UTF_8))
        }

    fun clientProof(
        verifier: ByteArray,
        bridgeId: String,
        clientId: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
    ): ByteArray = hmac(verifier, AUTH_LABEL, bridgeId, clientId, nonceS, nonceC)

    fun serverProof(
        verifier: ByteArray,
        bridgeId: String,
        clientId: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
    ): ByteArray = hmac(verifier, SERVER_LABEL, bridgeId, clientId, nonceC, nonceS)

    private fun hmac(
        key: ByteArray,
        label: String,
        bridgeId: String,
        clientId: String,
        first: ByteArray,
        second: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in listOf(label, bridgeId, clientId)) {
            mac.update(part.toByteArray(Charsets.UTF_8))
            mac.update(0)
        }
        mac.update(first)
        mac.update(second)
        return mac.doFinal()
    }

    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)

    fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
