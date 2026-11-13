package dev.videoeditor.core.mcp.bridge

import java.security.SecureRandom

/** Phone-side session authentication (B5). Pure JVM. */
interface TokenVerifier {
    /** Fresh 32-byte server nonce for one connection. */
    fun newChallenge(): ByteArray

    /**
     * Checks a bridge.auth proof in constant time. Performs the same work for unknown clients.
     * Applies the auth rate limits. On success also returns the proof the server must send back.
     */
    suspend fun verify(
        clientId: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
        proof: ByteArray,
    ): VerifyResult

    /** True while all unauthenticated connections are refused (global rate limit). */
    fun globallyThrottled(): Boolean = false

    sealed interface VerifyResult {
        class Accepted(
            val client: PairedClient,
            val serverProof: ByteArray,
        ) : VerifyResult

        /** One indistinguishable failure for unknown ids, wrong proofs, revoked and expired clients. */
        data object Rejected : VerifyResult

        data class Throttled(
            val retryAfterMs: Long,
        ) : VerifyResult
    }
}

data class AuthLimits(
    val perClientFailures: Int = 5,
    val globalFailures: Int = 20,
    val windowMs: Long = 60_000,
    val refusalMs: Long = 60_000,
    val idleExpiryMs: Long = 90L * 24 * 60 * 60 * 1000,
)

class DefaultTokenVerifier(
    private val store: ClientStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val limits: AuthLimits = AuthLimits(),
    /** Called when a client is revoked for idleness. */
    private val onExpired: (String) -> Unit = {},
) : TokenVerifier {
    private val dummyKey = BridgeCrypto.randomBytes(32, random)
    private val failures = HashMap<String, ArrayDeque<Long>>()
    private val globalFailures = ArrayDeque<Long>()
    private val blockedUntil = HashMap<String, Long>()
    private var globalBlockedUntil = 0L

    override fun newChallenge(): ByteArray = BridgeCrypto.randomBytes(BridgeCrypto.NONCE_BYTES, random)

    @Synchronized
    override fun globallyThrottled(): Boolean = globalBlockedUntil > clock()

    override suspend fun verify(
        clientId: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
        proof: ByteArray,
    ): TokenVerifier.VerifyResult = verifyBlocking(clientId, nonceS, nonceC, proof)

    @Synchronized
    fun verifyBlocking(
        clientId: String,
        nonceS: ByteArray,
        nonceC: ByteArray,
        proof: ByteArray,
    ): TokenVerifier.VerifyResult {
        val now = clock()
        if (globalBlockedUntil > now) return TokenVerifier.VerifyResult.Throttled(globalBlockedUntil - now)
        blockedUntil[clientId]?.let { if (it > now) return TokenVerifier.VerifyResult.Throttled(it - now) }

        val stored = store.verifier(clientId)
        val key = stored ?: dummyKey
        val expected = BridgeCrypto.clientProof(key, store.bridgeId, clientId, nonceS, nonceC)
        val match = BridgeCrypto.constantTimeEquals(expected, proof)
        val client = store.list().firstOrNull { it.clientId == clientId }
        if (stored != null && match && client != null && nonceC.size == BridgeCrypto.NONCE_BYTES) {
            val lastUsed = client.lastUsedAtEpochMs ?: client.createdAtEpochMs
            if (now - lastUsed > limits.idleExpiryMs) {
                store.remove(clientId)
                onExpired(clientId)
                return fail(clientId, now)
            }
            store.touch(clientId, now)
            val serverProof = BridgeCrypto.serverProof(key, store.bridgeId, clientId, nonceS, nonceC)
            return TokenVerifier.VerifyResult.Accepted(client.copy(lastUsedAtEpochMs = now), serverProof)
        }
        return fail(clientId, now)
    }

    private fun fail(
        clientId: String,
        now: Long,
    ): TokenVerifier.VerifyResult {
        val q = failures.getOrPut(clientId.take(64)) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() >= limits.windowMs) q.removeFirst()
        if (q.size >= limits.perClientFailures) {
            blockedUntil[clientId] = now + limits.refusalMs
            q.clear()
        }
        globalFailures.addLast(now)
        while (globalFailures.isNotEmpty() &&
            now - globalFailures.first() >= limits.windowMs
        ) {
            globalFailures.removeFirst()
        }
        if (globalFailures.size >= limits.globalFailures) {
            globalBlockedUntil = now + limits.refusalMs
            globalFailures.clear()
        }
        if (failures.size > 1024) failures.clear()
        return TokenVerifier.VerifyResult.Rejected
    }
}
