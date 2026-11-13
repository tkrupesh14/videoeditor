package dev.videoeditor.core.mcp.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Where paired clients live. The phone implementation keeps only an encrypted one-way verifier per client
 * (B4); the token itself is never stored.
 */
interface ClientStore {
    val bridgeId: String

    fun list(): List<PairedClient>

    fun verifier(clientId: String): ByteArray?

    fun add(
        client: PairedClient,
        verifier: ByteArray,
    )

    fun touch(
        clientId: String,
        nowEpochMs: Long,
    )

    fun remove(clientId: String)

    fun removeAll()
}

/** For tests and the JVM. */
class InMemoryClientStore(
    override val bridgeId: String,
) : ClientStore {
    private val clients = LinkedHashMap<String, Pair<PairedClient, ByteArray>>()

    @Synchronized override fun list(): List<PairedClient> = clients.values.map { it.first }

    @Synchronized override fun verifier(clientId: String): ByteArray? = clients[clientId]?.second?.copyOf()

    @Synchronized override fun add(
        client: PairedClient,
        verifier: ByteArray,
    ) {
        clients[client.clientId] = client to verifier.copyOf()
    }

    @Synchronized override fun touch(
        clientId: String,
        nowEpochMs: Long,
    ) {
        val c = clients[clientId] ?: return
        clients[clientId] = c.first.copy(lastUsedAtEpochMs = nowEpochMs) to c.second
    }

    @Synchronized override fun remove(clientId: String) {
        clients.remove(clientId)
    }

    @Synchronized override fun removeAll() = clients.clear()
}

data class PairingOffer(
    val code: String,
    val qrUri: String,
    val expiresAtEpochMs: Long,
)

sealed interface PairingState {
    data object Closed : PairingState

    data class Offering(
        val offer: PairingOffer,
        val attemptsLeft: Int,
    ) : PairingState

    data class AwaitingApproval(
        val requestId: String,
        val clientName: String,
    ) : PairingState

    data class LockedOut(
        val untilEpochMs: Long,
    ) : PairingState
}

sealed interface RedeemResult {
    /** The token leaves the phone here, once. */
    class Paired(
        val credentials: BridgeCredentials,
    ) : RedeemResult

    data object WrongCode : RedeemResult

    data object NotOffering : RedeemResult

    data object Denied : RedeemResult

    data object TooManyClients : RedeemResult

    data class LockedOut(
        val retryAfterMs: Long,
    ) : RedeemResult
}

/** Pairing (B3). UI-facing on the phone; the logic is pure so it runs in JVM tests. */
interface PairingManager {
    val pairing: StateFlow<PairingState>
    val clients: StateFlow<List<PairedClient>>

    /** Called when the pairing screen opens or the user taps "New code". */
    suspend fun beginPairing(): PairingOffer

    /** Called when the pairing screen closes. Invalidates any live code. */
    fun cancelPairing()

    /** Called by the bridge on bridge.pair. Suspends until the user answers or the approval timeout passes. */
    suspend fun redeem(
        code: String,
        clientName: String,
    ): RedeemResult

    /** Called by the confirmation dialog. */
    fun answer(
        requestId: String,
        allow: Boolean,
        scopes: Set<BridgeScope>,
    )

    suspend fun revoke(clientId: String)

    suspend fun revokeAll()
}

class DefaultPairingManager(
    private val store: ClientStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val socketName: String = BridgeEndpoint.SOCKET_NAME,
    private val suggestedPort: Int = BridgeEndpoint.DEFAULT_PORT,
    private val limits: PairingLimits = PairingLimits(),
    /** Called after a revocation so live sessions are closed (null = all clients). */
    private val onRevoked: suspend (clientId: String?) -> Unit = {},
) : PairingManager {
    private val mutex = Mutex()
    private val _pairing = MutableStateFlow<PairingState>(PairingState.Closed)
    override val pairing: StateFlow<PairingState> = _pairing.asStateFlow()
    private val _clients = MutableStateFlow(store.list())
    override val clients: StateFlow<List<PairedClient>> = _clients.asStateFlow()

    private var offer: PairingOffer? = null
    private var attemptsLeft = 0
    private var burnedInRow = 0
    private var lockedUntil = 0L

    @Volatile private var redeeming = false
    private var pending: Pair<String, CompletableDeferred<Set<BridgeScope>?>>? = null

    override suspend fun beginPairing(): PairingOffer =
        mutex.withLock {
            val now = clock()
            val code = BridgeCrypto.newPairingCode(random)
            val o =
                PairingOffer(
                    code,
                    "vedit-pair://v1?sock=$socketName&port=$suggestedPort&bridge=${store.bridgeId}&code=$code",
                    now + limits.codeTtlMs,
                )
            if (lockedUntil > now) {
                _pairing.value = PairingState.LockedOut(lockedUntil)
                return o.copy(code = "", qrUri = "")
            }
            offer = o
            attemptsLeft = limits.attemptsPerCode
            _pairing.value = PairingState.Offering(o, attemptsLeft)
            o
        }

    override fun cancelPairing() {
        offer = null
        pending?.second?.complete(null)
        if (_pairing.value !is PairingState.LockedOut) _pairing.value = PairingState.Closed
    }

    override suspend fun redeem(
        code: String,
        clientName: String,
    ): RedeemResult {
        val decision = CompletableDeferred<Set<BridgeScope>?>()
        mutex.withLock {
            val now = clock()
            if (lockedUntil > now) return RedeemResult.LockedOut(lockedUntil - now)
            if (redeeming) return RedeemResult.NotOffering
            val o = offer
            if (o == null || now >= o.expiresAtEpochMs) {
                offer = null
                return RedeemResult.NotOffering
            }
            if (!BridgeCrypto.constantTimeEquals(o.code, code)) {
                attemptsLeft--
                if (attemptsLeft <= 0) {
                    offer = null
                    burnedInRow++
                    if (burnedInRow >= limits.burnedCodesBeforeLockout) {
                        burnedInRow = 0
                        lockedUntil = now + limits.lockoutMs
                        _pairing.value = PairingState.LockedOut(lockedUntil)
                    } else {
                        _pairing.value = PairingState.Closed
                    }
                } else {
                    _pairing.value = PairingState.Offering(o, attemptsLeft)
                }
                redeeming = true
            } else {
                offer = null
                burnedInRow = 0
                if (store.list().size >= limits.maxClients) {
                    _pairing.value = PairingState.Closed
                    return RedeemResult.TooManyClients
                }
                redeeming = true
                val requestId = "pr_" + BridgeCrypto.b64url(BridgeCrypto.randomBytes(9, random))
                pending = requestId to decision
                _pairing.value = PairingState.AwaitingApproval(requestId, clientName.take(MAX_NAME))
                return@withLock
            }
            // Wrong code: hold the single pairing slot during the delay.
            try {
                delay(limits.wrongCodeDelayMs)
            } finally {
                redeeming = false
            }
            return RedeemResult.WrongCode
        }
        try {
            val scopes = withTimeoutOrNull(limits.approvalTimeoutMs) { decision.await() }
            if (scopes.isNullOrEmpty()) return RedeemResult.Denied
            return mutex.withLock {
                val now = clock()
                val token = BridgeCrypto.newToken(random)
                val clientId = BridgeCrypto.newClientId(random)
                store.add(
                    PairedClient(clientId, clientName.take(MAX_NAME), scopes, now, null),
                    BridgeCrypto.verifier(token),
                )
                _clients.value = store.list()
                RedeemResult.Paired(BridgeCredentials(store.bridgeId, clientId, token, scopes))
            }
        } finally {
            pending = null
            redeeming = false
            if (_pairing.value is PairingState.AwaitingApproval) _pairing.value = PairingState.Closed
        }
    }

    override fun answer(
        requestId: String,
        allow: Boolean,
        scopes: Set<BridgeScope>,
    ) {
        val p = pending ?: return
        if (p.first != requestId) return
        p.second.complete(if (allow) scopes else null)
    }

    override suspend fun revoke(clientId: String) {
        mutex.withLock {
            store.remove(clientId)
            _clients.value = store.list()
        }
        onRevoked(clientId)
    }

    override suspend fun revokeAll() {
        mutex.withLock {
            store.removeAll()
            _clients.value = store.list()
        }
        onRevoked(null)
    }

    /** Re-reads the store (after idle expiry or a lastUsed update). */
    fun refreshClients() {
        _clients.value = store.list()
    }

    companion object {
        const val MAX_NAME = 64
    }
}

data class PairingLimits(
    val codeTtlMs: Long = 120_000,
    val attemptsPerCode: Int = 5,
    val burnedCodesBeforeLockout: Int = 3,
    val lockoutMs: Long = 10 * 60_000,
    val wrongCodeDelayMs: Long = 1_000,
    val approvalTimeoutMs: Long = 60_000,
    val maxClients: Int = 8,
)
